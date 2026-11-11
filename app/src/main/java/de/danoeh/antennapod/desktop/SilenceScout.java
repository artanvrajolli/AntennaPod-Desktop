package de.danoeh.antennapod.desktop;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

/**
 * A second, silent player that runs ahead of the one being listened to and draws the
 * {@link SilenceMap} skipping works from.
 *
 * <p>JavaFX gives no way to look at audio before it plays, and skipping without that meant
 * jumping blind. A player at volume 0 still reports its spectrum (it is tapped before the
 * volume), plays at eight times speed, and stamps every frame with its place in the episode -
 * as long as it is never seeked: after a seek an MP3's frames carry the time played instead.
 * So the scout only ever starts at a position and plays on from there; when playback moves
 * somewhere it has not looked, it is replaced by a new one.
 *
 * <p>It stays between {@link #RESUME_AHEAD_MS} and {@link #AHEAD_MS} in front of playback and
 * pauses in between; at the end of the episode it is let go and only its map stays. Nothing here
 * touches the playing player: if the scout cannot open the source or falls behind, there is no
 * map for that stretch and nothing is skipped.
 */
final class SilenceScout {
    /** How far ahead of playback the scout scans before it pauses. */
    static final long AHEAD_MS = 90_000;
    /** How close playback has to come before a paused scout carries on. */
    static final long RESUME_AHEAD_MS = 30_000;
    /**
     * Playback up to this far past the scanned stretch is left to the scout to catch up with
     * (a moment at its speed); further out, a new one is started where playback is.
     */
    static final long CATCH_UP_MS = 30_000;
    /** Nothing is scanned this close to the end: there is nothing left to skip to. */
    static final long END_MARGIN_MS = 2_000;
    /** A scout that reports nothing for this long is given up on. */
    static final long STALL_MS = 20_000;
    /** A source that failed is left alone this long. */
    static final long RETRY_MS = 30_000;
    static final double FILE_RATE = 8.0;
    /** A stream is scanned more gently: the scout is a second download next to the one playing. */
    static final double STREAM_RATE = 4.0;
    private static final double SPECTRUM_INTERVAL_S = 0.05;
    private static final long FRAME_MS = 50;

    private final SilenceMap map;
    private final int spectrumFloorDb;
    private final AtomicBoolean queued = new AtomicBoolean();
    /** Whether a scout exists or is wanted; lets {@link #stop()} cost nothing when there is none. */
    private volatile boolean active;
    // what follow() was last asked for; read on the FX thread
    private volatile String wantedSource;
    private volatile long wantedPositionMs;
    private volatile long wantedDurationMs;
    // FX thread only
    private MediaPlayer player;
    private String source;
    private long startedAtMs;
    private boolean paused;
    private boolean finished;
    private long lastScannedMs;
    private long lastProgressAt;
    private String failedSource;
    private long failedAt;

    SilenceScout(SilenceMap map, int spectrumFloorDb) {
        this.map = map;
        this.spectrumFloorDb = spectrumFloorDb;
    }

    /**
     * Keeps the scout ahead of playback. Cheap and safe from any thread; the work happens on the
     * FX thread, one request at a time.
     *
     * @param source     what to scan (a file or stream URI), or null for nothing
     * @param positionMs where playback is
     * @param durationMs the episode's length, or 0 if unknown
     */
    void follow(String source, long positionMs, long durationMs) {
        if (source == null) {
            stop();
            return;
        }
        active = true;
        wantedSource = source;
        wantedPositionMs = positionMs;
        wantedDurationMs = durationMs;
        post();
    }

    /** Throws the scout and its map away. Safe from any thread. */
    void stop() {
        if (!active) {
            return;
        }
        active = false;
        wantedSource = null;
        post();
    }

    private void post() {
        if (queued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                queued.set(false);
                try {
                    apply();
                } catch (Exception e) {
                    // the scout is optional: without it nothing is skipped
                    e.printStackTrace();
                    discard();
                }
            });
        }
    }

    private void apply() {
        String wanted = wantedSource;
        if (wanted == null) {
            discard();
            map.clear();
            return;
        }
        long positionMs = wantedPositionMs;
        long now = System.currentTimeMillis();
        if ((player != null || finished) && wanted.equals(source) && covered(positionMs)) {
            if (player == null) {
                // scanned to the end: the map is all that is needed
                return;
            }
            long scannedMs = map.scannedToMs();
            long aheadMs = Math.max(scannedMs, startedAtMs) - positionMs;
            if (!paused && aheadMs >= AHEAD_MS) {
                player.pause();
                paused = true;
            } else if (paused && aheadMs <= RESUME_AHEAD_MS) {
                player.play();
                paused = false;
                lastProgressAt = now;
            } else if (scannedMs != lastScannedMs) {
                lastScannedMs = scannedMs;
                lastProgressAt = now;
            } else if (!paused && now - lastProgressAt > STALL_MS) {
                // a source that will not come: starting it over and over would only hammer it
                discard();
                map.clear();
                failed(wanted);
            }
            return;
        }
        if (wanted.equals(failedSource) && now - failedAt < RETRY_MS) {
            return;
        }
        long durationMs = wantedDurationMs;
        if (durationMs > 0 && positionMs > durationMs - END_MARGIN_MS) {
            // nothing left to scan
            return;
        }
        discard();
        map.clear();
        start(wanted, positionMs);
    }

    private boolean covered(long positionMs) {
        // from where this scout started to as far as it has looked, or soon will have
        return positionMs >= startedAtMs
                && positionMs <= Math.max(map.scannedToMs(), startedAtMs) + CATCH_UP_MS;
    }

    private void start(String wanted, long positionMs) {
        MediaPlayer scout;
        try {
            scout = new MediaPlayer(new Media(wanted));
        } catch (Exception e) {
            failed(wanted);
            return;
        }
        player = scout;
        source = wanted;
        startedAtMs = positionMs;
        paused = false;
        finished = false;
        lastScannedMs = -1;
        lastProgressAt = System.currentTimeMillis();
        // never heard: the spectrum is tapped ahead of both
        scout.setVolume(0);
        scout.setMute(true);
        scout.setRate(wanted.startsWith("file:") ? FILE_RATE : STREAM_RATE);
        scout.setStartTime(Duration.millis(positionMs));
        scout.setAudioSpectrumInterval(SPECTRUM_INTERVAL_S);
        scout.setAudioSpectrumThreshold(spectrumFloorDb);
        scout.setAudioSpectrumListener((timestamp, duration, magnitudes, phases) -> {
            if (player == scout) {
                map.add(Math.round(timestamp * 1000), FRAME_MS, magnitudes);
            }
        });
        scout.setOnEndOfMedia(() -> {
            if (player == scout) {
                // the file handle and the audio buffer go; the map of what it saw stays
                discard();
                source = wanted;
                finished = true;
            }
        });
        scout.setOnError(() -> {
            if (player == scout) {
                discard();
                map.clear();
                failed(wanted);
            }
        });
        scout.play();
    }

    private void failed(String wanted) {
        failedSource = wanted;
        failedAt = System.currentTimeMillis();
    }

    /** Lets go of the scout player; like the playing one, it is torn down off the FX thread. */
    private void discard() {
        MediaPlayer previous = player;
        player = null;
        source = null;
        finished = false;
        if (previous == null) {
            return;
        }
        try {
            previous.setAudioSpectrumListener(null);
        } catch (Exception e) {
            // it is being thrown away anyway
        }
        Thread disposer = new Thread(() -> {
            try {
                previous.stop();
                previous.dispose();
            } catch (Exception e) {
                // it is being thrown away anyway
            }
        }, "silence-scout-dispose");
        disposer.setDaemon(true);
        disposer.start();
    }
}
