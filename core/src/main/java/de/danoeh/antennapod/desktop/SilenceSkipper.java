package de.danoeh.antennapod.desktop;

public final class SilenceSkipper {
    public static final double SILENCE_THRESHOLD_DB = -55.0;
    private static final long ENTER_SILENCE_MS = 500;
    private static final long EXIT_SILENCE_MS = 200;

    private boolean silent;
    private boolean loud;
    private long quietSinceMs = -1;
    private long loudSinceMs = -1;

    public boolean update(float[] magnitudes, long nowMs) {
        if (magnitudes == null || magnitudes.length == 0) {
            return silent;
        }
        double loudest = Double.NEGATIVE_INFINITY;
        for (float magnitude : magnitudes) {
            if (magnitude > loudest) {
                loudest = magnitude;
            }
        }
        loud = loudest >= SILENCE_THRESHOLD_DB;
        if (loudest < SILENCE_THRESHOLD_DB) {
            loudSinceMs = -1;
            if (quietSinceMs < 0) {
                quietSinceMs = nowMs;
            }
            if (!silent && nowMs - quietSinceMs >= ENTER_SILENCE_MS) {
                silent = true;
            }
        } else {
            quietSinceMs = -1;
            if (loudSinceMs < 0) {
                loudSinceMs = nowMs;
            }
            if (silent && nowMs - loudSinceMs >= EXIT_SILENCE_MS) {
                silent = false;
            }
        }
        return silent;
    }

    public void reset() {
        silent = false;
        loud = false;
        quietSinceMs = -1;
        loudSinceMs = -1;
    }

    /**
     * Whether the most recent spectrum update contained audible sound, without the
     * hysteresis {@link #update} applies to its silence verdict. Lets callers stop
     * fast-forwarding the moment sound returns instead of after the exit delay.
     */
    public boolean isAudioLoud() {
        return loud;
    }
}
