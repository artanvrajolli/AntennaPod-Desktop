package de.danoeh.antennapod.desktop;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SleepTimer {
    public enum Mode {
        OFF, AFTER_MINUTES, END_OF_EPISODE
    }

    public interface Listener {
        void onTimerExpired();
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sleep-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Listener listener;
    private ScheduledFuture<?> future;
    /**
     * Counts schedules and cancels. An expiry already running when the timer is restarted or
     * cancelled finds a newer generation and does nothing, instead of switching the new timer off.
     */
    private long generation;
    private Mode mode = Mode.OFF;
    private long deadlineMs;

    public SleepTimer(Listener listener) {
        this.listener = listener;
    }

    public synchronized void startMinutes(long minutes) {
        cancelLocked();
        mode = Mode.AFTER_MINUTES;
        deadlineMs = System.currentTimeMillis() + minutes * 60_000L;
        persist();
        schedule(minutes * 60_000L);
    }

    synchronized void startMillis(long millis) {
        cancelLocked();
        mode = Mode.AFTER_MINUTES;
        deadlineMs = System.currentTimeMillis() + millis;
        persist();
        schedule(millis);
    }

    public synchronized void startEndOfEpisode() {
        cancelLocked();
        mode = Mode.END_OF_EPISODE;
        deadlineMs = 0;
        persist();
    }

    public synchronized void cancel() {
        cancelLocked();
        mode = Mode.OFF;
        deadlineMs = 0;
        persist();
    }

    private void schedule(long delayMs) {
        long scheduled = generation;
        future = scheduler.schedule(() -> expire(scheduled), delayMs, TimeUnit.MILLISECONDS);
    }

    private void cancelLocked() {
        generation++;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public synchronized Mode getMode() {
        return mode;
    }

    public synchronized long getRemainingMs() {
        if (mode != Mode.AFTER_MINUTES) {
            return -1;
        }
        return Math.max(deadlineMs - System.currentTimeMillis(), 0);
    }

    public synchronized void restore() {
        String savedMode = DesktopPreferences.getSleepTimerMode();
        if ("end".equals(savedMode)) {
            startEndOfEpisode();
        } else if (savedMode.startsWith("minutes")) {
            long savedDeadline = DesktopPreferences.getSleepTimerDeadline();
            long remaining = savedDeadline - System.currentTimeMillis();
            if (remaining > 0) {
                cancelLocked();
                mode = Mode.AFTER_MINUTES;
                deadlineMs = savedDeadline;
                schedule(remaining);
            } else {
                DesktopPreferences.setSleepTimerMode("off");
            }
        }
    }

    private void expire(long scheduled) {
        synchronized (this) {
            if (scheduled != generation) {
                return;
            }
            mode = Mode.OFF;
            deadlineMs = 0;
            future = null;
            // inside the lock, so it cannot land after a timer started meanwhile has saved itself
            DesktopPreferences.setSleepTimerMode("off");
        }
        listener.onTimerExpired();
    }

    private void persist() {
        if (mode == Mode.END_OF_EPISODE) {
            DesktopPreferences.setSleepTimerMode("end");
        } else if (mode == Mode.AFTER_MINUTES) {
            DesktopPreferences.setSleepTimerMode("minutes");
            DesktopPreferences.setSleepTimerDeadline(deadlineMs);
        } else {
            DesktopPreferences.setSleepTimerMode("off");
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
