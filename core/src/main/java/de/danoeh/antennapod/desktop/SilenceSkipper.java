package de.danoeh.antennapod.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Decides whether the audio currently playing is silence worth fast-forwarding through.
 *
 * <p>The previous implementation took the loudest FFT band and compared it against a fixed
 * -55&nbsp;dB threshold. That misfired in both directions: a single hissing band kept real
 * silence "loud", while quiet speech with its energy spread across bands dropped below the
 * threshold and got sped up. On top of that the player ducks the volume while fast-forwarding,
 * and the detector was reading the ducked (post-volume) audio, so once it wrongly entered
 * silence the ducked speech kept looking like silence and whole spoken passages were skipped.
 *
 * <p>This version instead follows the detectors that proved reliable elsewhere:
 *
 * <ul>
 *   <li>{@code SilenceSkippingAudioProcessor} in ExoPlayer / AndroidX Media3 (which is also what
 *       the Android AntennaPod and Twire's ExoPlayer-based player build on): an absolute level
 *       below which audio counts as silent, a minimum silence duration before anything happens,
 *       and keeping a little silence (fades/padding) around speech so onsets are never clipped.
 *   <li>{@code vantezzen/skip-silence} (TECH.md): RMS level in dBFS, a dynamic threshold derived
 *       from the noise floor (15th percentile of recent audio + a few dB, clamped), a Schmitt
 *       trigger with hysteresis so borderline noise does not flicker, ~500&nbsp;ms of sustained
 *       silence before speeding up, and snapping back to normal speed the instant sound returns.
 * </ul>
 *
 * <p>Adapted to what JavaFX offers — periodic FFT magnitudes in dB per band, no PCM tap and no
 * way to delay the audio for a lookahead:
 *
 * <ul>
 *   <li>Level is the <em>average power</em> across all bands (converted back to dB), which
 *       approximates overall loudness. Broadband speech stays loud even when no single band peaks,
 *       and a lone hissing band cannot hold off silence detection on its own.
 *   <li>The threshold adapts: 15th percentile of roughly the last 10&nbsp;s of audio (the noise
 *       floor estimate) plus {@link #FLOOR_MARGIN_DB}, clamped to {@link #MIN_THRESHOLD_DB} …
 *       {@link #MAX_THRESHOLD_DB}. No per-episode tuning needed; quiet studio recordings and
 *       noisy rooms each get their own threshold.
 *   <li>A Schmitt trigger ({@link #HYSTERESIS_DB}) separates entering silence from leaving it, so
 *       borderline levels do not flap the playback rate ten times a second.
 *   <li>Silence must hold for {@link #ENTER_SILENCE_MS} (word gaps never trigger it); sound
 *       restores normal speed immediately via {@link #isAudioLoud()}, because without an audio
 *       lookahead any exit delay would play the first syllable at 4x.
 *   <li>The current duck (volume) factor is compensated back out, so detection always works on
 *       the pre-volume level and ducking can no longer latch silence on through speech.
 * </ul>
 *
 * <p>On top of the adaptive verdict there is a second, much stricter one for <em>complete</em>
 * silence — stretches of (near-)digital silence such as the gaps between segments. Only that
 * verdict is acted on: the player seeks straight over it instead of playing it at any speed.
 * Its fixed threshold ({@link #COMPLETE_SILENCE_DB}) sits more than 15&nbsp;dB below even quiet
 * speech, so spoken audio can never trigger a skip; the moment sound returns
 * ({@link #COMPLETE_SOUND_DB}) the verdict drops, so a skip never starts inside speech.
 */
public final class SilenceSkipper {
    /** Threshold used before enough audio has been seen to adapt; conservative. */
    public static final double SILENCE_THRESHOLD_DB = -50.0;
    /** Silence must hold this long before it is fast-forwarded (word gaps stay untouched). */
    static final long ENTER_SILENCE_MS = 600;
    /** The hysteresis verdict needs loudness held this long before it flips back. */
    static final long EXIT_SILENCE_MS = 150;
    /** How far above the enter threshold the exit threshold sits (Schmitt trigger). */
    static final double HYSTERESIS_DB = 4.0;
    /** How far above the measured noise floor the silence threshold sits. */
    static final double FLOOR_MARGIN_DB = 10.0;
    /** The adaptive threshold never leaves this range. */
    static final double MIN_THRESHOLD_DB = -55.0;
    static final double MAX_THRESHOLD_DB = -30.0;
    /** Average power at or below this is complete (digital) silence, skipped over entirely. */
    static final double COMPLETE_SILENCE_DB = -57.0;
    /** Sound above this ends complete silence immediately (a small hysteresis band). */
    static final double COMPLETE_SOUND_DB = -54.0;
    /** Complete silence must hold this long before the first skip. */
    static final long COMPLETE_ENTER_MS = 400;
    /** Roughly how much recent audio the noise-floor estimate covers. */
    private static final int MAX_HISTORY = 200;

    private boolean silent;
    private boolean loud;
    private double lastLevelDb = Double.NEGATIVE_INFINITY;
    private long quietSinceMs = -1;
    private long loudSinceMs = -1;
    private boolean completeSilent;
    private long completeQuietSinceMs = -1;
    private final Deque<Double> history = new ArrayDeque<>();

    /**
     * Feeds one spectrum update through the detector.
     *
     * @param magnitudes per-band magnitudes in dB, as delivered by JavaFX
     * @param nowMs      current time, used for the enter/exit hold durations
     * @return whether the audio is currently considered silence worth skipping
     */
    public boolean update(float[] magnitudes, long nowMs) {
        return update(magnitudes, nowMs, 1.0);
    }

    /**
     * Feeds one spectrum update through the detector, compensating the player's volume ducking.
     *
     * @param magnitudes per-band magnitudes in dB, as delivered by JavaFX
     * @param nowMs      current time, used for the enter/exit hold durations
     * @param duckGain   current volume factor applied by the player (1.0 = full volume); the
     *                   attenuation is added back so detection sees the pre-volume level
     * @return whether the audio is currently considered silence worth skipping
     */
    public synchronized boolean update(float[] magnitudes, long nowMs, double duckGain) {
        if (magnitudes == null || magnitudes.length == 0) {
            return silent;
        }
        double levelDb = levelDb(magnitudes, duckGain);
        if (Double.isNaN(levelDb) || Double.isInfinite(levelDb)) {
            return silent;
        }
        lastLevelDb = levelDb;
        remember(levelDb);

        updateCompleteSilence(levelDb, nowMs);

        double enterThreshold = thresholdDb();
        double exitThreshold = enterThreshold + HYSTERESIS_DB;
        loud = levelDb > exitThreshold;

        if (levelDb < enterThreshold) {
            loudSinceMs = -1;
            if (quietSinceMs < 0) {
                quietSinceMs = nowMs;
            }
            if (!silent && nowMs - quietSinceMs >= ENTER_SILENCE_MS) {
                silent = true;
            }
        } else if (loud) {
            quietSinceMs = -1;
            if (loudSinceMs < 0) {
                loudSinceMs = nowMs;
            }
            if (silent && nowMs - loudSinceMs >= EXIT_SILENCE_MS) {
                silent = false;
            }
        } else {
            // between the thresholds: neither quiet enough to arm silence nor loud enough to
            // leave it — hold the current verdict instead of flickering
            quietSinceMs = -1;
            loudSinceMs = -1;
        }
        return silent;
    }

    public synchronized void reset() {
        silent = false;
        loud = false;
        lastLevelDb = Double.NEGATIVE_INFINITY;
        quietSinceMs = -1;
        loudSinceMs = -1;
        completeSilent = false;
        completeQuietSinceMs = -1;
        history.clear();
    }

    /**
     * Whether the most recent spectrum update contained audible sound, without the
     * hysteresis {@link #update} applies to its silence verdict. Lets callers stop
     * fast-forwarding the moment sound returns instead of after the exit delay.
     */
    public synchronized boolean isAudioLoud() {
        return loud;
    }

    /**
     * Whether the audio is currently in a stretch of complete silence worth skipping over.
     * Unlike the adaptive verdict above, this uses a fixed strict threshold far below speech,
     * so it can only ever fire on (near-)digital silence — never on spoken audio.
     */
    public synchronized boolean isCompletelySilent() {
        return completeSilent;
    }

    private void updateCompleteSilence(double levelDb, long nowMs) {
        if (levelDb <= COMPLETE_SILENCE_DB) {
            if (completeQuietSinceMs < 0) {
                completeQuietSinceMs = nowMs;
            }
            if (!completeSilent && nowMs - completeQuietSinceMs >= COMPLETE_ENTER_MS) {
                completeSilent = true;
            }
        } else if (levelDb > COMPLETE_SOUND_DB) {
            completeSilent = false;
            completeQuietSinceMs = -1;
        }
        // between the two: hold the current verdict instead of flickering
    }

    /** The overall level of the most recent update, in dB. */
    double lastLevelDb() {
        return lastLevelDb;
    }

    /** The currently effective silence threshold, in dB. */
    double thresholdDb() {
        if (history.isEmpty()) {
            return SILENCE_THRESHOLD_DB;
        }
        return clamp(noiseFloorDb() + FLOOR_MARGIN_DB, MIN_THRESHOLD_DB, MAX_THRESHOLD_DB);
    }

    /**
     * Overall level of one spectrum update: mean power across all bands, back in dB. Averaging
     * power (rather than taking the loudest band) is what keeps broadband-but-quiet speech above
     * the threshold while a single hissing band alone still reads as silence.
     */
    static double levelDb(float[] magnitudes, double duckGain) {
        double sumPower = 0.0;
        int count = 0;
        for (float magnitude : magnitudes) {
            if (Float.isNaN(magnitude) || Float.isInfinite(magnitude)) {
                continue;
            }
            sumPower += Math.pow(10.0, Math.min(magnitude, 0.0f) / 10.0);
            count++;
        }
        if (count == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double levelDb = 10.0 * Math.log10(Math.max(sumPower / count, 1e-12));
        if (!(duckGain <= 0.0) && duckGain < 1.0) {
            // the player turned the volume down by duckGain; add the attenuation back so the
            // detector judges the episode, not the ducking
            levelDb -= 20.0 * Math.log10(duckGain);
        }
        return levelDb;
    }

    /** 15th percentile of recent levels: the noise floor speech sits on top of. */
    private double noiseFloorDb() {
        List<Double> sorted = new ArrayList<>(history);
        Collections.sort(sorted);
        int index = (int) (0.15 * (sorted.size() - 1));
        return sorted.get(index);
    }

    private void remember(double levelDb) {
        history.addLast(levelDb);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
