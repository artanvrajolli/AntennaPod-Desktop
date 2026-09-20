package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

public final class PlaybackManager {
    public interface Listener {
        void onStateChanged();

        void onPositionChanged(int positionMs, int durationMs);

        void onLoadingChanged(boolean loading);

        void onError(String message);
    }

    private final DesktopDatabase database;
    private final Listener listener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** Separate pool for health checks: player work on {@link #scheduler} may block in native code. */
    private final ScheduledExecutorService healthScheduler =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "playback-health");
                thread.setDaemon(true);
                return thread;
            });

    private MediaPlayer player;
    private volatile FeedMedia currentMedia;
    private List<FeedItem> queue = List.of();
    private int queueIndex = -1;
    private ScheduledFuture<?> saveTask;
    private java.util.function.Consumer<FeedMedia> playActionRecorder;
    private java.util.function.Consumer<FeedMedia> autoDeleteHandler;
    private boolean stopAfterCurrent;
    private int pendingSeekMs = -1;
    private boolean suppressNextPlayAction;
    private final SilenceSkipper silenceSkipper = new SilenceSkipper();
    private Media currentFxMedia;
    private ScheduledFuture<?> loadWatchTask;
    private ScheduledFuture<?> stallWatchTask;
    private volatile boolean mediaReady;
    private volatile long loadStartedAt;
    private long stalledSince;
    private boolean stallNotified;
    private volatile FeedMedia abortedMedia;

    private static final double SILENCE_RATE_BOOST = 4.0;
    private static final double MAX_RATE = 8.0;
    /** How long a stream may take to become ready before playback is given up. */
    private static final long LOAD_TIMEOUT_MS = 30_000;
    /** How long a running stream may stay stalled before playback is given up. */
    private static final long STALL_TIMEOUT_MS = 60_000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 1_000;

    private float effectiveSpeed() {
        float rate = DesktopPreferences.getPlaybackSpeed();
        try {
            if (currentMedia != null && currentMedia.getItem() != null
                    && currentMedia.getItem().getFeedId() != 0) {
                FeedPrefs prefs = database.getFeedPrefs(currentMedia.getItem().getFeedId());
                rate = prefs.effectiveSpeed(rate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rate;
    }

    private void notifyAutoDelete(FeedMedia media) {
        if (autoDeleteHandler != null) {
            try {
                autoDeleteHandler.accept(media);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public PlaybackManager(DesktopDatabase database, Listener listener) {
        this.database = database;
        this.listener = listener;
    }

    public synchronized void play(FeedItem item, List<FeedItem> contextQueue) {
        playAt(item, contextQueue, -1);
    }

    public synchronized void playAt(FeedItem item, List<FeedItem> contextQueue, int positionMs) {
        if (item == null || item.getMedia() == null) {
            return;
        }
        pendingSeekMs = positionMs;
        queue = contextQueue != null ? List.copyOf(contextQueue) : List.of(item);
        queueIndex = 0;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getId() == item.getId()) {
                queueIndex = i;
                break;
            }
        }
        startPlayback(item.getMedia());
    }

    public synchronized void playMedia(FeedMedia media) {
        queue = List.of();
        queueIndex = -1;
        startPlayback(media);
    }

    private void startPlayback(FeedMedia media) {
        saveAndRecordCurrent();
        stopPlayer();
        currentMedia = media;
        String source = media.localFileAvailable() && media.getLocalFileUrl() != null
                ? new java.io.File(media.getLocalFileUrl()).toURI().toString()
                : media.getStreamUrl();
        try {
            currentFxMedia = new Media(source);
            player = new MediaPlayer(currentFxMedia);
        } catch (Exception e) {
            currentMedia = null;
            currentFxMedia = null;
            notifyState();
            notifyLoading(false);
            notifyError("Could not play \"" + titleOf(media) + "\": " + e.getMessage());
            return;
        }
        startHealthWatch();
        player.setRate(effectiveSpeed());
        player.setVolume(DesktopPreferences.getDefaultVolume());
        player.statusProperty().addListener((obs, oldStatus, newStatus) -> {
            if (newStatus == MediaPlayer.Status.PLAYING || newStatus == MediaPlayer.Status.PAUSED
                    || newStatus == MediaPlayer.Status.STOPPED) {
                mediaReady = true;
                notifyState();
            }
        });
        applyVolumeBoost();
        configureSilenceSkipping();
        int startPosition = Math.max(media.getPosition(), DesktopPreferences.getSkipIntroSec() * 1000);
        player.setOnReady(() -> {
            mediaReady = true;
            int duration = getDuration();
            if (duration > 0) {
                currentMedia.setDuration(duration);
                saveMedia();
            }
            if (pendingSeekMs >= 0) {
                player.seek(new Duration(Math.min(pendingSeekMs, Math.max(duration - 1000, 0))));
                pendingSeekMs = -1;
            } else if (startPosition > 0 && startPosition < duration - 5000) {
                player.seek(new Duration(startPosition));
            }
            player.play();
            notifyLoading(false);
            notifyState();
        });
        player.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            listener.onPositionChanged((int) newTime.toMillis(), getDuration());
            checkSkipEnding((int) newTime.toMillis());
        });
        player.setOnEndOfMedia(this::finishPlayback);
        player.setOnError(() -> abortPlayback(describeError()));
        markStarted(currentMedia);
        saveTask = scheduler.scheduleWithFixedDelay(this::saveMedia, 5, 5, TimeUnit.SECONDS);
        notifyState();
        notifyLoading(true);
    }

    private synchronized void finishPlayback() {
        if (currentMedia == null) {
            return;
        }
        recordFinishedAction();
        currentMedia.setPosition(0);
        currentMedia.setPlayedDuration(currentMedia.getPlayedDuration() + currentMedia.getDuration());
        saveMedia();
        markPlayed(currentMedia);
        notifyAutoDelete(currentMedia);
        if (stopAfterCurrent) {
            stopAfterCurrent = false;
            stop();
        } else {
            playNext();
        }
    }

    private synchronized void checkSkipEnding(int positionMs) {
        int skipEndingMs = DesktopPreferences.getSkipEndingSec() * 1000;
        if (skipEndingMs <= 0 || currentMedia == null || player == null) {
            return;
        }
        int duration = getDuration();
        if (duration > 0 && positionMs >= duration - skipEndingMs) {
            finishPlayback();
        }
    }

    private void applyVolumeBoost() {
        try {
            int boostDb = DesktopPreferences.getVolumeBoostDb();
            javafx.scene.media.AudioEqualizer equalizer = player.getAudioEqualizer();
            equalizer.setEnabled(boostDb > 0);
            if (boostDb > 0) {
                for (javafx.scene.media.EqualizerBand band : equalizer.getBands()) {
                    band.setGain(Math.min(boostDb, 12));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void setSilenceSkipping(boolean enabled) {
        DesktopPreferences.setSkipSilence(enabled);
        if (player == null) {
            return;
        }
        if (!enabled) {
            player.setAudioSpectrumListener(null);
            applySilenceRate(false);
            return;
        }
        configureSilenceSkipping();
    }

    public synchronized boolean isSilenceSkipping() {
        return DesktopPreferences.getSkipSilence();
    }

    private void configureSilenceSkipping() {
        silenceSkipper.reset();
        if (!DesktopPreferences.getSkipSilence()) {
            return;
        }
        player.setAudioSpectrumListener((timestamp, duration, magnitudes, phases) -> {
            boolean silent = silenceSkipper.update(magnitudes, System.currentTimeMillis());
            applySilenceRate(silent);
        });
    }

    private void applySilenceRate(boolean silent) {
        if (player == null) {
            return;
        }
        float target = silent
                ? (float) Math.min(effectiveSpeed() * SILENCE_RATE_BOOST, MAX_RATE)
                : effectiveSpeed();
        if (Math.abs(player.getRate() - target) > 0.01f) {
            player.setRate(target);
        }
    }

    public synchronized void setPlayActionRecorder(java.util.function.Consumer<FeedMedia> recorder) {
        this.playActionRecorder = recorder;
    }

    public synchronized void setAutoDeleteHandler(java.util.function.Consumer<FeedMedia> handler) {
        this.autoDeleteHandler = handler;
    }

    public synchronized void setStopAfterCurrent(boolean stopAfterCurrent) {
        this.stopAfterCurrent = stopAfterCurrent;
    }

    public synchronized boolean isStopAfterCurrent() {
        return stopAfterCurrent;
    }

    public synchronized void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            saveMedia();
            recordPlayAction();
        } else {
            player.play();
        }
        notifyState();
    }

    public synchronized void stop() {
        saveMedia();
        recordPlayAction();
        stopPlayer();
        currentMedia = null;
        notifyState();
        notifyLoading(false);
    }

    private void recordPlayAction() {
        if (suppressNextPlayAction) {
            suppressNextPlayAction = false;
            return;
        }
        if (playActionRecorder != null && currentMedia != null) {
            try {
                playActionRecorder.accept(currentMedia);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void saveAndRecordCurrent() {
        if (currentMedia == null || player == null) {
            return;
        }
        saveMedia();
        recordPlayAction();
    }

    private void recordFinishedAction() {
        currentMedia.setPosition(currentMedia.getDuration());
        recordPlayAction();
        suppressNextPlayAction = true;
    }

    public synchronized void seek(int positionMs) {
        if (player != null) {
            player.seek(new Duration(clampPosition(positionMs)));
        }
    }

    public synchronized void skip(int deltaMs) {
        if (player == null || deltaMs == 0) {
            return;
        }
        seek((int) player.getCurrentTime().toMillis() + deltaMs);
    }

    private int clampPosition(int positionMs) {
        int duration = getDuration();
        return Math.max(0, duration > 0 ? Math.min(positionMs, duration) : positionMs);
    }

    public synchronized void setRate(float rate) {
        DesktopPreferences.setPlaybackSpeed(rate);
        if (player != null) {
            player.setRate(rate);
        }
    }

    public synchronized void setVolume(double volume) {
        if (player != null) {
            player.setVolume(volume);
        }
    }

    public synchronized void playNext() {
        if (queueIndex >= 0 && queueIndex + 1 < queue.size()) {
            queueIndex++;
            FeedItem next = queue.get(queueIndex);
            if (next.getMedia() != null) {
                startPlayback(next.getMedia());
                return;
            }
        }
        stop();
    }

    public synchronized void playPrevious() {
        if (player != null && player.getCurrentTime().toSeconds() > 5) {
            player.seek(Duration.ZERO);
            return;
        }
        if (queueIndex > 0) {
            queueIndex--;
            FeedItem previous = queue.get(queueIndex);
            if (previous.getMedia() != null) {
                startPlayback(previous.getMedia());
                return;
            }
        }
        stop();
    }

    public synchronized FeedMedia getCurrentMedia() {
        return currentMedia;
    }

    public synchronized boolean isPlaying() {
        return player != null && player.getStatus() == MediaPlayer.Status.PLAYING;
    }

    public synchronized int getPosition() {
        return player != null ? (int) player.getCurrentTime().toMillis()
                : (currentMedia != null ? currentMedia.getPosition() : 0);
    }

    static int resolveDuration(Duration duration, int fallback) {
        double millis = duration == null ? Double.NaN : duration.toMillis();
        if (Double.isFinite(millis) && millis > 0 && millis < Integer.MAX_VALUE
                && FeedMedia.isValidDuration((long) millis)) {
            return (int) millis;
        }
        return FeedMedia.isValidDuration(fallback) ? fallback : 0;
    }

    public synchronized int getDuration() {
        return resolveDuration(player != null ? player.getTotalDuration() : null,
                currentMedia != null ? currentMedia.getDuration() : 0);
    }

    public synchronized void shutdown() {
        saveAndRecordCurrent();
        scheduler.shutdownNow();
        healthScheduler.shutdownNow();
        stopPlayer();
    }

    private void stopPlayer() {
        MediaPlayer previous;
        synchronized (this) {
            cancelPlaybackTasks();
            previous = player;
            player = null;
            currentFxMedia = null;
            mediaReady = false;
            stalledSince = 0;
            stallNotified = false;
        }
        disposeLater(previous);
    }

    private void cancelPlaybackTasks() {
        if (saveTask != null) {
            saveTask.cancel(false);
            saveTask = null;
        }
        if (loadWatchTask != null) {
            loadWatchTask.cancel(false);
            loadWatchTask = null;
        }
        if (stallWatchTask != null) {
            stallWatchTask.cancel(false);
            stallWatchTask = null;
        }
    }

    /**
     * JavaFX media can block forever inside native code when a stream stops delivering data, so
     * the player is torn down on its own thread instead of freezing whoever asked for it.
     */
    private void disposeLater(MediaPlayer previous) {
        if (previous == null) {
            return;
        }
        Thread disposer = new Thread(() -> {
            try {
                previous.stop();
                previous.dispose();
            } catch (Exception e) {
                // the player is being thrown away anyway
            }
        }, "playback-dispose");
        disposer.setDaemon(true);
        disposer.start();
    }

    private void startHealthWatch() {
        loadStartedAt = System.currentTimeMillis();
        mediaReady = false;
        stalledSince = 0;
        stallNotified = false;
        abortedMedia = null;
        if (loadWatchTask != null) {
            loadWatchTask.cancel(false);
        }
        if (stallWatchTask != null) {
            stallWatchTask.cancel(false);
        }
        loadWatchTask = healthScheduler.scheduleWithFixedDelay(this::checkLoadTimeout,
                HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        stallWatchTask = healthScheduler.scheduleWithFixedDelay(this::checkPlaybackHealth,
                HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Fails playback that never becomes ready. Deliberately free of player calls and locks: it has
     * to keep working even when native media code is wedged.
     */
    private void checkLoadTimeout() {
        if (currentMedia == null || mediaReady) {
            return;
        }
        if (System.currentTimeMillis() - loadStartedAt >= LOAD_TIMEOUT_MS) {
            abortPlayback("timed out while loading the stream");
        }
    }

    /**
     * JavaFX media never gives up on a stream that stops delivering data, so loading would spin
     * forever. Watch the player and abort playback instead of waiting indefinitely.
     */
    private void checkPlaybackHealth() {
        try {
            runPlaybackHealthCheck();
        } catch (Throwable t) {
            // a scheduled task that throws is cancelled silently, which would leave the UI stuck
            t.printStackTrace();
        }
    }

    private void runPlaybackHealthCheck() {
        String failure = null;
        Boolean stalledChange = null;
        synchronized (this) {
            if (player == null || currentMedia == null) {
                return;
            }
            MediaPlayer.Status status = player.getStatus();
            long now = System.currentTimeMillis();
            boolean stalled = false;
            if (status == MediaPlayer.Status.HALTED || status == MediaPlayer.Status.DISPOSED) {
                failure = describeError();
            } else if (status == MediaPlayer.Status.STALLED) {
                if (stalledSince == 0) {
                    stalledSince = now;
                }
                stalled = true;
                if (now - stalledSince >= STALL_TIMEOUT_MS) {
                    failure = "the stream stalled and did not recover";
                }
            } else {
                stalledSince = 0;
                if (status == MediaPlayer.Status.PLAYING || status == MediaPlayer.Status.PAUSED) {
                    mediaReady = true;
                }
            }
            if (stalled != stallNotified) {
                stallNotified = stalled;
                stalledChange = stalled;
            }
        }
        if (failure != null) {
            abortPlayback(failure);
        } else if (stalledChange != null) {
            notifyLoading(stalledChange);
        }
    }

    /** Stops the player and reports why, so the UI never keeps showing a loading state. */
    private void abortPlayback(String reason) {
        FeedMedia failed = currentMedia;
        if (failed == null || failed == abortedMedia) {
            return;
        }
        abortedMedia = failed;
        notifyLoading(false);
        notifyState();
        notifyError("Stopped \"" + titleOf(failed) + "\": " + reason);
        // tearing the player down can block in native code, so keep it off the calling thread
        Thread stopper = new Thread(() -> {
            synchronized (PlaybackManager.this) {
                if (currentMedia == failed) {
                    currentMedia = null;
                }
                stopPlayer();
            }
        }, "playback-abort");
        stopper.setDaemon(true);
        stopper.start();
    }

    private String describeError() {
        synchronized (this) {
            javafx.scene.media.MediaException error = player != null ? player.getError() : null;
            if (error == null && currentFxMedia != null) {
                error = currentFxMedia.getError();
            }
            String message = error != null ? error.getMessage() : null;
            return message != null && !message.isEmpty() ? message : "the stream could not be loaded";
        }
    }

    private static String titleOf(FeedMedia media) {
        if (media.getItem() != null && media.getItem().getTitle() != null) {
            return media.getItem().getTitle();
        }
        return "episode";
    }

    private void saveMedia() {
        FeedMedia media;
        MediaPlayer activePlayer;
        synchronized (this) {
            media = currentMedia;
            activePlayer = player;
        }
        if (media == null || activePlayer == null) {
            return;
        }
        int position;
        int duration;
        if (javafx.application.Platform.isFxApplicationThread()) {
            // layout thread: reading the player here can block the whole UI in native media code
            position = media.getPosition();
            duration = media.getDuration();
        } else {
            try {
                // never hold the lock while calling into the player: native media calls can block
                position = (int) activePlayer.getCurrentTime().toMillis();
                duration = resolveDuration(activePlayer.getTotalDuration(), media.getDuration());
            } catch (Exception e) {
                return;
            }
        }
        media.setPosition(position);
        if (duration > 0) {
            media.setDuration(duration);
        }
        media.setLastPlayedTimeStatistics(System.currentTimeMillis());
        try {
            database.updateMedia(media);
        } catch (Exception e) {
            e.printStackTrace();
        }
        DesktopPreferences.setLastPlayedMediaId(media.getId());
    }

    private void markStarted(FeedMedia media) {
        try {
            if (media.getItem() != null) {
                media.getItem().setPlayed(false);
                database.setItemState(media.getItem().getId(), media.getItem().getPlayState());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void markPlayed(FeedMedia media) {
        try {
            if (media.getItem() != null) {
                media.getItem().setPlayed(true);
                database.setItemState(media.getItem().getId(), media.getItem().getPlayState());
            }
            media.setLastPlayedTimeHistory(new java.util.Date());
            database.updateMedia(media);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyState() {
        Platform.runLater(listener::onStateChanged);
    }

    private void notifyLoading(boolean loading) {
        Platform.runLater(() -> listener.onLoadingChanged(loading));
    }

    private void notifyError(String message) {
        Platform.runLater(() -> listener.onError(message));
    }
}
