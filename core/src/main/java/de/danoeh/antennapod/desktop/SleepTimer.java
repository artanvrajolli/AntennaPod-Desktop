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
        future = scheduler.schedule(this::expire, minutes * 60_000L, TimeUnit.MILLISECONDS);
    }

    synchronized void startMillis(long millis) {
        cancelLocked();
        mode = Mode.AFTER_MINUTES;
        deadlineMs = System.currentTimeMillis() + millis;
        persist();
        future = scheduler.schedule(this::expire, millis, TimeUnit.MILLISECONDS);
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

    private void cancelLocked() {
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
        } else if (savedMode.startsWith("minutes:")) {
            long savedDeadline = DesktopPreferences.getSleepTimerDeadline();
            long remaining = savedDeadline - System.currentTimeMillis();
            if (remaining > 0) {
                cancelLocked();
                mode = Mode.AFTER_MINUTES;
                deadlineMs = savedDeadline;
                future = scheduler.schedule(this::expire, remaining, TimeUnit.MILLISECONDS);
            } else {
                DesktopPreferences.setSleepTimerMode("off");
            }
        }
    }

    private void expire() {
        synchronized (this) {
            mode = Mode.OFF;
            deadlineMs = 0;
            future = null;
        }
        DesktopPreferences.setSleepTimerMode("off");
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
