package de.danoeh.antennapod.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where the silence in an episode is, so the player can jump over it and land just before the
 * sound comes back.
 *
 * <p>Skipping used to work on what the playing player had just heard: 400&nbsp;ms of quiet,
 * then a jump one second ahead - into audio nobody had looked at. On a spoken test track it cut
 * 17 of 43 seconds of speech: the start of nearly every sentence after a pause, and a quietly
 * spoken passage almost entirely, because its "complete silence" level, an average over all
 * spectrum bands, really sat near -28&nbsp;dBFS.
 *
 * <p>The map is filled from audio the listener has not reached yet (a second, muted player
 * scanning ahead; see {@code SilenceScout} in the app), so a skip knows both ends of the
 * silence it is in:
 *
 * <ul>
 *   <li>A frame is quiet when it is {@link #BELOW_SPEECH_DB} below the speech around it (the
 *       90th percentile of the last ten seconds of sound), and never above
 *       {@link #HIGHEST_THRESHOLD_DB}: a quiet speaker is measured against themselves, not
 *       against a fixed line.
 *   <li>A run of quiet ends with the first frame that is not, however short the sound.
 *   <li>A skip starts {@link #FRONT_PAD_MS} into the run and lands {@link #BACK_PAD_MS} before
 *       its end, so a word's tail and the breath before the next one stay, and it has to save
 *       at least {@link #MIN_SKIP_MS}: the pauses of ordinary speech are left alone.
 * </ul>
 */
public final class SilenceMap {
    /** Silence left playing before a skip starts. */
    public static final long FRONT_PAD_MS = 150;
    /**
     * Silence left playing after a skip, before the sound returns. Generous on purpose: it also
     * has to absorb a seek that lands a little late and a soft onset under the threshold.
     */
    public static final long BACK_PAD_MS = 350;
    /** A jump that saves less than this is not worth a seek. */
    public static final long MIN_SKIP_MS = 600;
    /** The shortest silence anything is skipped from. */
    static final long MIN_RUN_MS = FRONT_PAD_MS + MIN_SKIP_MS + BACK_PAD_MS;
    /** How far below the speech around it audio has to be to count as silence. */
    static final double BELOW_SPEECH_DB = 30.0;
    /** The threshold stays in this range whatever the speech level. */
    static final double LOWEST_THRESHOLD_DB = -60.0;
    static final double HIGHEST_THRESHOLD_DB = -42.0;
    /** The threshold until a second of sound has been seen. */
    static final double UNKNOWN_SPEECH_THRESHOLD_DB = -55.0;
    /** A run that has begun carries on up to this far above its threshold. */
    static final double HYSTERESIS_DB = 3.0;
    /** Frames of sound the speech level is taken from: ten seconds at 50 ms. */
    private static final int SPEECH_FRAMES = 200;
    private static final int MIN_SPEECH_FRAMES = 20;
    private static final int THRESHOLD_EVERY_FRAMES = 10;

    /** A jump the player may make now. */
    public static final class Skip {
        public final long targetMs;
        /** The level the silence was judged against, for checking it against what is playing. */
        public final double thresholdDb;

        Skip(long targetMs, double thresholdDb) {
            this.targetMs = targetMs;
            this.thresholdDb = thresholdDb;
        }
    }

    private static final class Run {
        final long startMs;
        long endMs;
        final double thresholdDb;

        Run(long startMs, double thresholdDb) {
            this.startMs = startMs;
            this.endMs = startMs;
            this.thresholdDb = thresholdDb;
        }
    }

    private final double floorDb;
    private final List<Run> runs = new ArrayList<>();
    private Run open;
    private long fromMs = -1;
    private long toMs = -1;
    private final double[] speech = new double[SPEECH_FRAMES];
    private int speechCount;
    private int speechNext;
    private int sinceThreshold;
    private double thresholdDb = UNKNOWN_SPEECH_THRESHOLD_DB;

    /**
     * @param spectrumFloorDb the spectrum's sensitivity threshold; bands at it carry no reading
     */
    public SilenceMap(double spectrumFloorDb) {
        this.floorDb = spectrumFloorDb;
    }

    /** Forgets everything: the scan starts over somewhere else. */
    public synchronized void clear() {
        runs.clear();
        open = null;
        fromMs = -1;
        toMs = -1;
        speechCount = 0;
        speechNext = 0;
        sinceThreshold = 0;
        thresholdDb = UNKNOWN_SPEECH_THRESHOLD_DB;
    }

    /**
     * Adds the next spectrum frame of the scan, in playing order.
     *
     * @param startMs    where in the episode the frame begins
     * @param lengthMs   how much audio it covers
     * @param magnitudes per-band magnitudes in dB, as delivered by JavaFX
     */
    public synchronized void add(long startMs, long lengthMs, float[] magnitudes) {
        add(startMs, lengthMs, AudioCompressor.levelDb(magnitudes, floorDb));
    }

    /** As {@link #add(long, long, float[])}, with the frame's level worked out; NaN is no sound at all. */
    synchronized void add(long startMs, long lengthMs, double levelDb) {
        if (fromMs < 0) {
            fromMs = startMs;
        } else if (startMs + lengthMs <= toMs) {
            return;
        } else if (startMs > toMs + lengthMs) {
            // frames went missing: nobody looked at that stretch, so no silence runs across it
            closeRun(toMs);
        }
        double limitDb = open != null ? open.thresholdDb + HYSTERESIS_DB : thresholdDb;
        if (Double.isNaN(levelDb) || levelDb <= limitDb) {
            if (open == null) {
                open = new Run(startMs, thresholdDb);
            }
        } else {
            closeRun(startMs);
            rememberSpeech(levelDb);
        }
        toMs = startMs + lengthMs;
        if (open != null) {
            open.endMs = toMs;
        }
    }

    /** How far the scan has got, or -1 before its first frame. */
    public synchronized long scannedToMs() {
        return toMs;
    }

    /**
     * The jump to make from this position, or null: only from inside a silence, past its first
     * {@link #FRONT_PAD_MS}, to {@link #BACK_PAD_MS} before the sound returns - or, while the
     * scan is still inside the same silence, before as far as it has looked.
     */
    public synchronized Skip skipFrom(long positionMs) {
        if (open != null && open.endMs - open.startMs >= MIN_RUN_MS) {
            Skip skip = skipWithin(open, positionMs);
            if (skip != null) {
                return skip;
            }
        }
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run run = runs.get(i);
            if (run.endMs <= positionMs) {
                break;
            }
            Skip skip = skipWithin(run, positionMs);
            if (skip != null) {
                return skip;
            }
        }
        return null;
    }

    private static Skip skipWithin(Run run, long positionMs) {
        long targetMs = run.endMs - BACK_PAD_MS;
        if (positionMs >= run.startMs + FRONT_PAD_MS && targetMs - positionMs >= MIN_SKIP_MS) {
            return new Skip(targetMs, run.thresholdDb);
        }
        return null;
    }

    private void closeRun(long endMs) {
        if (open == null) {
            return;
        }
        open.endMs = endMs;
        if (open.endMs - open.startMs >= MIN_RUN_MS) {
            runs.add(open);
        }
        open = null;
    }

    private void rememberSpeech(double levelDb) {
        speech[speechNext] = levelDb;
        speechNext = (speechNext + 1) % SPEECH_FRAMES;
        speechCount = Math.min(speechCount + 1, SPEECH_FRAMES);
        if (++sinceThreshold >= THRESHOLD_EVERY_FRAMES || speechCount == MIN_SPEECH_FRAMES) {
            sinceThreshold = 0;
            thresholdDb = thresholdFor(speech, speechCount);
        }
    }

    /** The silence threshold for the sound seen lately: well below its 90th percentile. */
    static double thresholdFor(double[] levels, int count) {
        if (count < MIN_SPEECH_FRAMES) {
            return UNKNOWN_SPEECH_THRESHOLD_DB;
        }
        double[] sorted = Arrays.copyOf(levels, count);
        Arrays.sort(sorted);
        double speechDb = sorted[(int) (0.9 * (count - 1))];
        return Math.max(LOWEST_THRESHOLD_DB, Math.min(HIGHEST_THRESHOLD_DB, speechDb - BELOW_SPEECH_DB));
    }

    /** The threshold the next frame is judged against. */
    synchronized double thresholdDb() {
        return thresholdDb;
    }

    /**
     * What the playing player itself heard in the last moments, as a second opinion before a
     * skip: the map is drawn by another player, and a jump is only made while the one playing
     * agrees it is in silence. Should the two ever disagree about where they are in the file,
     * nothing is skipped.
     */
    public static final class Heard {
        /** Frames in a row that have to be quiet: 150 ms. */
        static final int FRAMES = 3;
        /** The newest frame may be no older than this; none arrive while paused or stalled. */
        static final long FRESH_MS = 300;

        private final double[] levels = new double[FRAMES];
        private int count;
        private int next;
        private long lastMs;

        /** Notes one spectrum frame of the playing player; NaN is no sound at all. */
        public synchronized void add(double levelDb, long nowMs) {
            levels[next] = levelDb;
            next = (next + 1) % FRAMES;
            count = Math.min(count + 1, FRAMES);
            lastMs = nowMs;
        }

        /** Forgets what was heard: after a seek it says nothing about where playback is now. */
        public synchronized void reset() {
            count = 0;
        }

        /** Whether everything heard lately was at or below the level. */
        public synchronized boolean quiet(double thresholdDb, long nowMs) {
            if (count < FRAMES || nowMs - lastMs > FRESH_MS) {
                return false;
            }
            for (double level : levels) {
                if (!Double.isNaN(level) && level > thresholdDb) {
                    return false;
                }
            }
            return true;
        }
    }
}
