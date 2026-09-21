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
            Executors.newScheduledThreadPool(3, runnable -> {
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
    private java.util.function.Consumer<FeedMedia> cacheStartedHandler;
    private java.util.function.Consumer<FeedMedia> cacheFinishedHandler;
    private Runnable resumeLastHandler;
    private boolean stopAfterCurrent;
    private int pendingSeekMs = -1;
    private boolean suppressNextPlayAction;
    private final SilenceSkipper silenceSkipper = new SilenceSkipper();
    private final SilenceBoost silenceBoost = new SilenceBoost();
    /** What the audio last sounded like: true while it is silence worth fast-forwarding through. */
    private volatile boolean silenceDetected;
    /** True while the audio spectrum is being watched, so the applier knows to do anything at all. */
    private volatile boolean silenceWatchActive;
    private Media currentFxMedia;
    private ScheduledFuture<?> loadWatchTask;
    private ScheduledFuture<?> stallWatchTask;
    private ScheduledFuture<?> silenceTask;
    private volatile boolean mediaReady;
    private volatile long loadStartedAt;
    private long stalledSince;
    private long stoppedSince;
    private boolean stallNotified;
    private volatile FeedMedia abortedMedia;
    /** The episode whose load timeout has already been acted on, so it is handled once. */
    private volatile FeedMedia loadTimeoutMedia;
    /** Consecutive load timeouts for the episode being started. */
    private int loadRetries;
    /** The episode we have already asked the cache to fetch, so it is asked once. */
    private FeedMedia cacheNotifiedFor;
    private volatile boolean everPlayed;
    private volatile int lastKnownPositionMs;
    /** True while the player reads a local file, false while it reads a network stream. */
    private volatile boolean playingFromFile;
    /** Duration the feed claims, kept before the player overwrites it with its own estimate. */
    private int declaredDurationMs;
    /** Consecutive restarts that did not get playback any further. */
    private int resumeAttempts;
    private int resumeFromPositionMs;

    /** How often what the audio sounded like is turned into a rate and a volume on the player. */
    private static final long SILENCE_APPLY_INTERVAL_MS = 100;
    /** Audio plays this fraction of the normal volume while fast-forwarding through silence. */
    private static final double SILENCE_DUCK_FACTOR = 0.15;
    /** How much of the duck is applied per spectrum update (~0.1 s apart). */
    private static final double DUCK_DOWN_STEP = 0.3;
    /** Raising the volume back happens slower, so returning speech never pops. */
    private static final double DUCK_UP_STEP = 0.25;
    /** User volume (0..1). Kept separate from the player, which gets the ducked value. */
    private volatile double userVolume = clampVolume(DesktopPreferences.getDefaultVolume());
    /** 1.0 = normal volume, SILENCE_DUCK_FACTOR = ducked for silence skipping. */
    private volatile double duck = 1.0;
    /** How long a stream may take to become ready before playback is given up. */
    private static final long LOAD_TIMEOUT_MS = 30_000;
    /** A stream that will not start is tried again this many times before it is reported. */
    static final int MAX_LOAD_RETRIES = 1;
    /** How long a running stream may stay stalled before playback is given up. */
    private static final long STALL_TIMEOUT_MS = 60_000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 1_000;
    /** Time allowed between end-of-media and the queue advancing before a STOPPED player is a fault. */
    private static final long UNEXPECTED_STOP_GRACE_MS = 3_000;
    /** Playback that ends further than this from the end of the episode did not end on its own. */
    static final int PREMATURE_END_TOLERANCE_MS = 10_000;
    /** A restart has to get at least this much further, or the episode really was over. */
    static final int MIN_RESUME_PROGRESS_MS = 3_000;
    static final int MAX_RESUME_ATTEMPTS = 3;

    /**
     * The speed this episode plays at, worked out once rather than on demand.
     *
     * <p>Silence skipping asks for it from the audio spectrum callback, which JavaFX delivers ten
     * times a second while playing. Resolving it reads the preferences store and queries the
     * database, and that query takes the lock every other database operation takes — feed
     * refreshes, sync, the episode cache. Doing that from the media thread put a contended lock
     * directly in the audio path, where a refresh writing a few hundred episodes could block the
     * callback. It is read once per episode now and kept here.
     */
    private volatile float effectiveSpeed = 1.0f;

    private float effectiveSpeed() {
        return effectiveSpeed;
    }

    /** Re-reads the speed from preferences and the feed's own override. Never call from audio. */
    private void refreshEffectiveSpeed() {
        float rate = DesktopPreferences.getPlaybackSpeed();
        try {
            FeedMedia media = currentMedia;
            if (media != null && media.getItem() != null && media.getItem().getFeedId() != 0) {
                FeedPrefs prefs = database.getFeedPrefs(media.getItem().getFeedId());
                rate = prefs.effectiveSpeed(rate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        effectiveSpeed = rate;
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

    private void notifyCacheStarted(FeedMedia media) {
        if (cacheStartedHandler != null) {
            try {
                cacheStartedHandler.accept(media);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void notifyCacheFinished(FeedMedia media) {
        if (cacheFinishedHandler != null) {
            try {
                cacheFinishedHandler.accept(media);
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
        int mediaDurationMs = FeedMedia.isValidDuration(media.getDuration()) ? media.getDuration() : 0;
        if (media != currentMedia) {
            resumeAttempts = 0;
            resumeFromPositionMs = 0;
            loadRetries = 0;
            declaredDurationMs = mediaDurationMs;
            // a different episode has its own source and its own bitrate, so what the last one
            // could not keep up with says nothing about this one
            silenceBoost.reset();
        } else {
            // a restart of the same episode: the player has already overwritten the stored
            // duration with its own estimate, so keep the longest value we ever saw
            declaredDurationMs = Math.max(declaredDurationMs, mediaDurationMs);
        }
        lastKnownPositionMs = 0;
        currentMedia = media;
        String playableFile = media.playableFileUrl();
        playingFromFile = playableFile != null;
        String source = playableFile != null
                ? new java.io.File(playableFile).toURI().toString()
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
        duck = 1.0;
        refreshEffectiveSpeed();
        player.setRate(effectiveSpeed());
        player.setVolume(userVolume);
        player.statusProperty().addListener((obs, oldStatus, newStatus) -> {
            if (newStatus == MediaPlayer.Status.PLAYING) {
                everPlayed = true;
                startCaching();
            }
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
                int target = pendingSeekMs;
                pendingSeekMs = -1;
                if (duration > 0 && target >= duration - 1000) {
                    // the player thinks the episode ends before where we wanted to resume. Playing
                    // on would start it again from the beginning, so accept that it is over.
                    notifyLoading(false);
                    completeEpisode();
                    return;
                }
                player.seek(new Duration(Math.max(target, 0)));
            } else if (startPosition > 0 && startPosition < duration - 5000) {
                player.seek(new Duration(startPosition));
            }
            player.play();
            notifyLoading(false);
            notifyState();
        });
        player.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            int positionMs = (int) newTime.toMillis();
            if (positionMs > 0) {
                lastKnownPositionMs = positionMs;
            }
            listener.onPositionChanged(positionMs, getDuration());
            checkSkipEnding(positionMs);
        });
        player.setOnEndOfMedia(this::finishPlayback);
        player.setOnError(() -> abortPlayback(describeError()));
        markStarted(currentMedia);
        saveTask = scheduler.scheduleWithFixedDelay(this::saveMedia, 5, 5, TimeUnit.SECONDS);
        notifyState();
        notifyLoading(true);
    }

    /**
     * Asks the cache to fetch this episode, once playback is actually running. Starting it any
     * earlier put a second full-speed download of the very same episode, plus the prefetch of the
     * next ones, against the stream during the window it needs to connect — which is what made
     * streams time out before they had begun.
     */
    private synchronized void startCaching() {
        FeedMedia media = currentMedia;
        if (media == null || media == cacheNotifiedFor) {
            return;
        }
        cacheNotifiedFor = media;
        notifyCacheStarted(media);
    }

    private synchronized void finishPlayback() {
        if (currentMedia == null) {
            return;
        }
        int positionMs = Math.max(lastKnownPositionMs, currentMedia.getPosition());
        if (shouldResume(positionMs)) {
            resumeAt(positionMs, "stopped early");
            return;
        }
        completeEpisode();
    }

    /** Marks the episode played and moves on. Only called once playback really is over. */
    private synchronized void completeEpisode() {
        if (currentMedia == null) {
            return;
        }
        recordFinishedAction();
        currentMedia.setPosition(0);
        currentMedia.setPlayedDuration(currentMedia.getPlayedDuration() + currentMedia.getDuration());
        saveMedia();
        markPlayed(currentMedia);
        notifyAutoDelete(currentMedia);
        notifyCacheFinished(currentMedia);
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
            silenceWatchActive = false;
            silenceDetected = false;
            player.setAudioSpectrumListener(null);
            // no spectrum updates will arrive to ramp back, so restore immediately
            duck = 1.0;
            player.setVolume(userVolume);
            player.setRate(effectiveSpeed());
            return;
        }
        configureSilenceSkipping();
    }

    public synchronized boolean isSilenceSkipping() {
        return DesktopPreferences.getSkipSilence();
    }

    private void configureSilenceSkipping() {
        silenceSkipper.reset();
        silenceDetected = false;
        if (!DesktopPreferences.getSkipSilence()) {
            silenceWatchActive = false;
            return;
        }
        silenceWatchActive = true;
        player.setAudioSpectrumListener((timestamp, duration, magnitudes, phases) -> {
            boolean silent = silenceSkipper.update(magnitudes, System.currentTimeMillis());
            // a loud frame ends the fast-forward immediately instead of after the exit
            // delay, so returning speech is never played at the boosted rate
            silenceDetected = silent && !silenceSkipper.isAudioLoud();
        });
    }

    /**
     * Turns what the audio last sounded like into a rate and a volume on the player.
     *
     * <p>This used to happen inside the audio spectrum callback, which is JavaFX's own media
     * thread calling back into the player it is decoding on. Two things went wrong there. The
     * callback only fires while the player is playing, so once a boosted stream drained its
     * buffer and stalled there was nothing left to lower the rate again — it resumed at the
     * boosted rate, drained the buffer once more, and the episode eventually gave up without ever
     * continuing. And the rate change went back into native media code from within its own
     * callback. Reading the state on a timer instead means the player is always brought back to
     * its normal rate when it is not playing, whatever the audio last said.
     */
    private void applySilenceRate() {
        MediaPlayer activePlayer;
        synchronized (this) {
            activePlayer = silenceWatchActive ? player : null;
        }
        if (activePlayer == null) {
            return;
        }
        MediaPlayer.Status status;
        try {
            status = activePlayer.getStatus();
        } catch (Exception e) {
            return;
        }
        long now = System.currentTimeMillis();
        if (status == MediaPlayer.Status.STALLED) {
            // the fast-forward is the first suspect for a dry buffer, so it goes before the
            // stall does rather than after the stream has been given up on
            silenceBoost.onStalled(now);
        } else if (status == MediaPlayer.Status.PLAYING) {
            silenceBoost.onPlaying();
        }
        boolean silent = silenceDetected && status == MediaPlayer.Status.PLAYING
                && silenceBoost.isAllowed(now);
        if (silent) {
            // fade the volume out first and only speed up once it is already quiet:
            // speeding up full-volume audio is what produces the loud pitch sound
            if (duck > SILENCE_DUCK_FACTOR) {
                duck = Math.max(SILENCE_DUCK_FACTOR, duck - DUCK_DOWN_STEP);
                setPlayerVolume(activePlayer, effectiveVolume());
            }
            if (duck <= SILENCE_DUCK_FACTOR + 0.001) {
                setPlayerRate(activePlayer, SilenceBoost.rateFor(effectiveSpeed(), playingFromFile));
            }
        } else {
            // back to normal speed immediately, then raise the volume again slowly
            setPlayerRate(activePlayer, effectiveSpeed());
            if (duck < 1.0) {
                duck = Math.min(1.0, duck + DUCK_UP_STEP);
                setPlayerVolume(activePlayer, effectiveVolume());
            }
        }
    }

    private void tickSilenceRate() {
        try {
            applySilenceRate();
        } catch (Throwable t) {
            // a scheduled task that throws is cancelled silently, which would strand the rate
            t.printStackTrace();
        }
    }

    private static void setPlayerRate(MediaPlayer target, float rate) {
        try {
            if (Math.abs(target.getRate() - rate) > 0.01f) {
                target.setRate(rate);
            }
        } catch (Exception e) {
            // the player is on its way out; whatever replaces it starts at its own rate
        }
    }

    private static void setPlayerVolume(MediaPlayer target, double volume) {
        try {
            target.setVolume(volume);
        } catch (Exception e) {
            // as above
        }
    }

    private double effectiveVolume() {
        return clampVolume(userVolume * duck);
    }

    private static double clampVolume(double volume) {
        return Math.max(0, Math.min(1, volume));
    }

    public synchronized void setPlayActionRecorder(java.util.function.Consumer<FeedMedia> recorder) {
        this.playActionRecorder = recorder;
    }

    public synchronized void setAutoDeleteHandler(java.util.function.Consumer<FeedMedia> handler) {
        this.autoDeleteHandler = handler;
    }

    /**
     * Hooks the episode cache into playback: {@code onStarted} fires when an episode begins (cache
     * it and prefetch the queue) and {@code onFinished} when it plays through to the end.
     */
    public synchronized void setCacheHandlers(java.util.function.Consumer<FeedMedia> onStarted,
                                              java.util.function.Consumer<FeedMedia> onFinished) {
        this.cacheStartedHandler = onStarted;
        this.cacheFinishedHandler = onFinished;
    }

    public synchronized void setStopAfterCurrent(boolean stopAfterCurrent) {
        this.stopAfterCurrent = stopAfterCurrent;
    }

    public synchronized boolean isStopAfterCurrent() {
        return stopAfterCurrent;
    }

    /**
     * What every play button in the app calls. With nothing loaded it hands over to the resume
     * handler instead of doing nothing, so the tray, the player bar and the space bar all behave
     * the same way and a new play button cannot forget to.
     */
    public void togglePlayPause() {
        Runnable resume;
        synchronized (this) {
            if (player != null) {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    player.pause();
                    saveMedia();
                    recordPlayAction();
                } else {
                    player.play();
                }
                notifyState();
                return;
            }
            resume = resumeLastHandler;
        }
        // outside the lock: resuming loads an episode, which comes straight back into this manager
        if (resume != null) {
            resume.run();
        }
    }

    /**
     * What to do when play is pressed with nothing loaded. Set by the app to pick the last episode
     * back up where it was left off.
     */
    public synchronized void setResumeLastHandler(Runnable handler) {
        this.resumeLastHandler = handler;
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
        effectiveSpeed = rate;
        if (player != null) {
            player.setRate(rate);
        }
    }

    public synchronized void setVolume(double volume) {
        userVolume = clampVolume(volume);
        if (player != null) {
            player.setVolume(effectiveVolume());
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

    /** The episodes queued up after the one playing now, in play order. */
    public synchronized List<FeedItem> upcomingQueue(int count) {
        if (count <= 0 || queueIndex < 0) {
            return List.of();
        }
        List<FeedItem> upcoming = new java.util.ArrayList<>();
        for (int i = queueIndex + 1; i < queue.size() && upcoming.size() < count; i++) {
            FeedItem item = queue.get(i);
            if (item.getMedia() != null) {
                upcoming.add(item);
            }
        }
        return upcoming;
    }

    public synchronized boolean isPlaying() {
        return player != null && player.getStatus() == MediaPlayer.Status.PLAYING;
    }

    /** Whether the player is reading a file on disk rather than pulling from the network. */
    public synchronized boolean isPlayingFromFile() {
        return player != null && playingFromFile;
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
            silenceWatchActive = false;
            silenceDetected = false;
            currentFxMedia = null;
            mediaReady = false;
            everPlayed = false;
            stalledSince = 0;
            stoppedSince = 0;
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
        if (silenceTask != null) {
            silenceTask.cancel(false);
            silenceTask = null;
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
        everPlayed = false;
        stalledSince = 0;
        stoppedSince = 0;
        stallNotified = false;
        abortedMedia = null;
        loadTimeoutMedia = null;
        if (loadWatchTask != null) {
            loadWatchTask.cancel(false);
        }
        if (stallWatchTask != null) {
            stallWatchTask.cancel(false);
        }
        if (silenceTask != null) {
            silenceTask.cancel(false);
        }
        loadWatchTask = healthScheduler.scheduleWithFixedDelay(this::checkLoadTimeout,
                HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        stallWatchTask = healthScheduler.scheduleWithFixedDelay(this::checkPlaybackHealth,
                HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        silenceTask = healthScheduler.scheduleWithFixedDelay(this::tickSilenceRate,
                SILENCE_APPLY_INTERVAL_MS, SILENCE_APPLY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Fails playback that never becomes ready. Deliberately free of player calls and locks: it has
     * to keep working even when native media code is wedged.
     */
    private void checkLoadTimeout() {
        FeedMedia stuck = currentMedia;
        if (stuck == null || mediaReady || stuck == loadTimeoutMedia) {
            return;
        }
        if (System.currentTimeMillis() - loadStartedAt < LOAD_TIMEOUT_MS) {
            return;
        }
        // claim it before doing anything, so the once-a-second watchdog does not fire again
        loadTimeoutMedia = stuck;
        Thread worker = new Thread(() -> handleLoadTimeout(stuck), "playback-load-timeout");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * A stream that has not started yet is given another go before it is reported: these are
     * usually slow rather than broken, and a second attempt costs the listener nothing but the
     * wait they were already having.
     */
    private void handleLoadTimeout(FeedMedia stuck) {
        synchronized (this) {
            if (currentMedia != stuck) {
                return;
            }
            if (loadRetries < MAX_LOAD_RETRIES) {
                loadRetries++;
                notifyError("\"" + titleOf(stuck) + "\" is slow to start, trying again");
                pendingSeekMs = Math.max(lastKnownPositionMs, stuck.getPosition());
                suppressNextPlayAction = true;
                notifyLoading(true);
                startPlayback(stuck);
                return;
            }
        }
        abortPlayback("timed out while loading the stream");
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
                stoppedSince = 0;
                if (stalledSince == 0) {
                    stalledSince = now;
                }
                stalled = true;
                if (now - stalledSince >= STALL_TIMEOUT_MS) {
                    failure = "the stream stalled and did not recover";
                }
            } else if (status == MediaPlayer.Status.STOPPED && everPlayed) {
                // nothing here ever stops a player it still owns. Wait out the moment between
                // end-of-media and the queue advancing, then treat it as a silent drop-out - but
                // only when restarting would actually read a better source than the one that quit.
                stalledSince = 0;
                if (stoppedSince == 0) {
                    stoppedSince = now;
                } else if (now - stoppedSince >= UNEXPECTED_STOP_GRACE_MS
                        && canResumeFromNewSource(lastKnownPositionMs)) {
                    failure = "playback stopped unexpectedly";
                }
            } else {
                stalledSince = 0;
                stoppedSince = 0;
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

    /**
     * Picks playback back up where it dropped out, or stops and reports why so the UI never keeps
     * showing a loading state. Everything runs on its own thread: the callers include the health
     * watchdogs, which must not take the lock or touch the player in case native code is wedged.
     */
    private void abortPlayback(String reason) {
        FeedMedia failed = currentMedia;
        if (failed == null || failed == abortedMedia) {
            return;
        }
        abortedMedia = failed;
        Thread stopper = new Thread(() -> {
            synchronized (PlaybackManager.this) {
                if (currentMedia != failed) {
                    return;
                }
                if (shouldResume(lastKnownPositionMs)) {
                    resumeAt(lastKnownPositionMs, reason);
                    return;
                }
                currentMedia = null;
                stopPlayer();
            }
            notifyLoading(false);
            notifyState();
            notifyError("Stopped \"" + titleOf(failed) + "\": " + reason);
        }, "playback-abort");
        stopper.setDaemon(true);
        stopper.start();
    }

    /** The longest duration we have any reason to believe in: the feed's or the player's. */
    private int trustedDurationMs() {
        return Math.max(getDuration(), declaredDurationMs);
    }

    /**
     * Whether playback that just ended really ended, or stopped short of the episode. JavaFX
     * reports a too-short total duration for some variable-bitrate streams and then fires
     * end-of-media there, which looks to the listener like playback stopping for no reason.
     */
    private boolean shouldResume(int positionMs) {
        if (currentMedia == null) {
            return false;
        }
        int durationMs = trustedDurationMs();
        if (!isPrematureStop(positionMs, durationMs)) {
            return false;
        }
        if (positionMs - resumeFromPositionMs >= MIN_RESUME_PROGRESS_MS) {
            // the last restart did get further, so this is a new stop rather than a retry loop
            resumeAttempts = 0;
        } else if (resumeAttempts > 0) {
            // the restart got nowhere, so the episode really is shorter than its duration claims
            // and reading it again would only decide the same thing
            return false;
        }
        return resumeAttempts < MAX_RESUME_ATTEMPTS;
    }

    /**
     * Whether playback stopped short of the end AND a complete local copy has appeared since it
     * started streaming, so a restart would read something the failing source could not give us.
     */
    private boolean canResumeFromNewSource(int positionMs) {
        return currentMedia != null && !playingFromFile && currentMedia.playableFileUrl() != null
                && isPrematureStop(positionMs, trustedDurationMs())
                && resumeAttempts < MAX_RESUME_ATTEMPTS;
    }

    static boolean isPrematureStop(int positionMs, int durationMs) {
        return positionMs > 0 && durationMs > 0 && positionMs < durationMs - PREMATURE_END_TOLERANCE_MS;
    }

    /** Rebuilds the player on the same episode and picks playback back up where it dropped out. */
    private void resumeAt(int positionMs, String reason) {
        FeedMedia media = currentMedia;
        resumeAttempts++;
        resumeFromPositionMs = positionMs;
        abortedMedia = null;
        pendingSeekMs = positionMs;
        // a restart is not the user pausing, so it must not turn into a sync play action
        suppressNextPlayAction = true;
        notifyError("\"" + titleOf(media) + "\" " + reason + " at " + formatPosition(positionMs)
                + ", resuming");
        notifyLoading(true);
        startPlayback(media);
        // after the restart, so the save on the way out of the old player cannot overwrite it
        media.setPosition(positionMs);
    }

    private static String formatPosition(int positionMs) {
        int totalSeconds = Math.max(positionMs, 0) / 1000;
        return String.format("%d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60,
                totalSeconds % 60);
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
