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
    }

    private final DesktopDatabase database;
    private final Listener listener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private MediaPlayer player;
    private FeedMedia currentMedia;
    private List<FeedItem> queue = List.of();
    private int queueIndex = -1;
    private ScheduledFuture<?> saveTask;
    private java.util.function.Consumer<FeedMedia> playActionRecorder;
    private java.util.function.Consumer<FeedMedia> autoDeleteHandler;
    private boolean stopAfterCurrent;
    private int pendingSeekMs = -1;

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
        stopPlayer();
        currentMedia = media;
        String source = media.localFileAvailable() && media.getLocalFileUrl() != null
                ? new java.io.File(media.getLocalFileUrl()).toURI().toString()
                : media.getStreamUrl();
        try {
            Media fxMedia = new Media(source);
            player = new MediaPlayer(fxMedia);
        } catch (Exception e) {
            currentMedia = null;
            notifyState();
            return;
        }
        player.setRate(effectiveSpeed());
        player.setVolume(DesktopPreferences.getDefaultVolume());
        applyVolumeBoost();
        int startPosition = Math.max(media.getPosition(), DesktopPreferences.getSkipIntroSec() * 1000);
        player.setOnReady(() -> {
            int duration = (int) player.getTotalDuration().toMillis();
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
        });
        player.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            listener.onPositionChanged((int) newTime.toMillis(), getDuration());
            checkSkipEnding((int) newTime.toMillis());
        });
        player.setOnEndOfMedia(this::finishPlayback);
        player.setOnError(() -> stopPlayer());
        markStarted(currentMedia);
        saveTask = scheduler.scheduleWithFixedDelay(this::saveMedia, 5, 5, TimeUnit.SECONDS);
        notifyState();
    }

    private synchronized void finishPlayback() {
        if (currentMedia == null) {
            return;
        }
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
    }

    private void recordPlayAction() {
        if (playActionRecorder != null && currentMedia != null) {
            try {
                playActionRecorder.accept(currentMedia);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public synchronized void seek(int positionMs) {
        if (player != null) {
            player.seek(new Duration(positionMs));
        }
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

    public synchronized int getDuration() {
        if (player != null && player.getTotalDuration() != null
                && player.getTotalDuration().greaterThan(Duration.ZERO)) {
            return (int) player.getTotalDuration().toMillis();
        }
        return currentMedia != null ? currentMedia.getDuration() : 0;
    }

    public void shutdown() {
        scheduler.shutdownNow();
        stopPlayer();
    }

    private void stopPlayer() {
        if (saveTask != null) {
            saveTask.cancel(false);
            saveTask = null;
        }
        if (player != null) {
            try {
                player.stop();
                player.dispose();
            } catch (Exception e) {
                // ignore
            }
            player = null;
        }
    }

    private void saveMedia() {
        FeedMedia media;
        int position;
        int duration;
        synchronized (this) {
            media = currentMedia;
            if (media == null || player == null) {
                return;
            }
            position = (int) player.getCurrentTime().toMillis();
            duration = getDuration();
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
}
