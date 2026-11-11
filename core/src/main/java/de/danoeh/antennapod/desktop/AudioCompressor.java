package de.danoeh.antennapod.desktop;

/**
 * Evens out loud and quiet audio: the gain to play the next stretch at, from how loud the last
 * one was.
 *
 * <p>The curve is the one Xtra's audio compressor sets on Android's {@code DynamicsProcessing}:
 * threshold -50&nbsp;dB, ratio 1.6, a 40&nbsp;dB knee and 10&nbsp;dB of make-up gain. Quiet
 * speech comes up by as much as 10&nbsp;dB, ordinary speech stays about where it was, and
 * loud passages are turned down.
 *
 * <p>JavaFX gives neither a compressor nor the samples, only periodic FFT magnitudes and an
 * equalizer, so this works on those: the level of each spectrum frame goes in, the gain for the
 * equalizer bands comes out. The spectrum is tapped <em>after</em> the equalizer (and before
 * the volume), so the gain already applied is taken back out to judge the episode and not our
 * own correction, and a new gain reaches the audio within a frame - some 300&nbsp;ms before
 * it is heard.
 *
 * <p>Two things keep a frame-based compressor from pumping on speech. The level is followed
 * quickly upwards ({@link #ATTACK_MS}) but slowly downwards ({@link #RELEASE_MS}), so the gaps
 * between words do not pull the gain up. And frames below {@link #GATE_DB} are not followed at
 * all: a pause keeps the gain of the speech around it instead of having its noise floor
 * raised.
 */
public final class AudioCompressor {
    static final double THRESHOLD_DB = -50.0;
    static final double RATIO = 1.6;
    static final double KNEE_DB = 40.0;
    static final double MAKEUP_DB = 10.0;
    /** The gain never leaves this range, whatever the level. */
    static final double MAX_GAIN_DB = MAKEUP_DB;
    static final double MIN_GAIN_DB = -12.0;
    /** Audio quieter than this is a pause, not something to level against. */
    static final double GATE_DB = -55.0;
    static final double ATTACK_MS = 30.0;
    static final double RELEASE_MS = 1000.0;
    /** Gains are handed out in steps of this size, so the equalizer is not rewritten every frame. */
    static final double STEP_DB = 0.25;
    /**
     * What the summed band power reads below the real RMS level: a -33 dBFS sine sums to -40,
     * -35 dBFS noise to -44.
     */
    static final double LEVEL_OFFSET_DB = 8.0;
    /** Longest gap between two frames that still counts as continuous audio. */
    private static final long MAX_FRAME_GAP_MS = 500;

    private final double floorDb;
    private double envelopeDb = Double.NaN;
    private double gainDb;
    private long lastFrameMs = -1;

    /**
     * @param spectrumFloorDb the spectrum's sensitivity threshold: bands at it carry no reading,
     *                        only the floor value, and are left out of the level
     */
    public AudioCompressor(double spectrumFloorDb) {
        this.floorDb = spectrumFloorDb;
    }

    /**
     * Feeds one spectrum frame through the compressor.
     *
     * @param magnitudes    per-band magnitudes in dB, as delivered by JavaFX
     * @param appliedGainDb the gain the equalizer carried while this frame went through it
     * @param nowMs         current time, for the attack and release
     * @return the gain in dB the audio should now be played at
     */
    public synchronized double update(float[] magnitudes, double appliedGainDb, long nowMs) {
        long elapsedMs = lastFrameMs < 0 ? MAX_FRAME_GAP_MS
                : Math.max(1, Math.min(nowMs - lastFrameMs, MAX_FRAME_GAP_MS));
        lastFrameMs = nowMs;
        double inputDb = levelDb(magnitudes, floorDb) - appliedGainDb;
        if (Double.isNaN(inputDb) || inputDb < GATE_DB) {
            return gainDb;
        }
        if (Double.isNaN(envelopeDb)) {
            envelopeDb = inputDb;
        } else {
            double timeConstantMs = inputDb > envelopeDb ? ATTACK_MS : RELEASE_MS;
            envelopeDb += (inputDb - envelopeDb) * (1.0 - Math.exp(-elapsedMs / timeConstantMs));
        }
        gainDb = Math.round(gainForLevel(envelopeDb) / STEP_DB) * STEP_DB;
        return gainDb;
    }

    /** The gain last handed out; 0 before any audio has been seen. */
    public synchronized double gainDb() {
        return gainDb;
    }

    public synchronized void reset() {
        envelopeDb = Double.NaN;
        gainDb = 0;
        lastFrameMs = -1;
    }

    /** The static curve: make-up gain, less what the ratio takes off above the (soft) threshold. */
    static double gainForLevel(double levelDb) {
        double over = levelDb - THRESHOLD_DB;
        double slope = 1.0 / RATIO - 1.0;
        double reduction;
        if (2 * over <= -KNEE_DB) {
            reduction = 0;
        } else if (2 * over >= KNEE_DB) {
            reduction = slope * over;
        } else {
            double intoKnee = over + KNEE_DB / 2;
            reduction = slope * intoKnee * intoKnee / (2 * KNEE_DB);
        }
        return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, MAKEUP_DB + reduction));
    }

    /**
     * Overall level of one spectrum frame, about the RMS level in dBFS: the power of every band
     * that carries a reading, summed. NaN when no band does.
     */
    public static double levelDb(float[] magnitudes, double floorDb) {
        if (magnitudes == null) {
            return Double.NaN;
        }
        double power = 0;
        for (float magnitude : magnitudes) {
            if (Float.isNaN(magnitude) || Float.isInfinite(magnitude) || magnitude <= floorDb + 0.5) {
                continue;
            }
            power += Math.pow(10.0, magnitude / 10.0);
        }
        return power <= 0 ? Double.NaN : 10.0 * Math.log10(power) + LEVEL_OFFSET_DB;
    }
}
