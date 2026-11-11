package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The audio compressor: quiet audio up, loud audio down, on the curve Xtra uses, without
 * chasing its own gain or lifting the noise in a pause.
 */
public class AudioCompressorTest {
    private static final double FLOOR_DB = -70;
    private static final int BANDS = 128;

    /** A frame whose overall level reads as the given RMS level, spread over a few bands. */
    private static float[] frame(double levelDb) {
        float[] magnitudes = new float[BANDS];
        java.util.Arrays.fill(magnitudes, (float) FLOOR_DB);
        int carrying = 4;
        double perBand = levelDb - AudioCompressor.LEVEL_OFFSET_DB - 10 * Math.log10(carrying);
        for (int i = 0; i < carrying; i++) {
            magnitudes[i] = (float) perBand;
        }
        return magnitudes;
    }

    /** Plays a steady level for a while, feeding back the gain the way the player does. */
    private static double settle(AudioCompressor compressor, double levelDb, long fromMs, long forMs) {
        double gain = compressor.gainDb();
        for (long time = fromMs; time <= fromMs + forMs; time += 50) {
            // the spectrum sits behind the equalizer: it sees the level with the gain on it
            gain = compressor.update(frame(levelDb + gain), gain, time);
        }
        return gain;
    }

    @Test
    public void theCurveIsXtras() {
        // threshold -50, ratio 1.6, knee 40, make-up 10
        assertEquals(10.0, AudioCompressor.gainForLevel(-90), 0.001);
        assertEquals(10.0, AudioCompressor.gainForLevel(-70), 0.001);
        assertEquals(8.125, AudioCompressor.gainForLevel(-50), 0.001);
        assertEquals(2.5, AudioCompressor.gainForLevel(-30), 0.001);
        assertEquals(-1.25, AudioCompressor.gainForLevel(-20), 0.001);
        assertEquals(-8.75, AudioCompressor.gainForLevel(0), 0.001);
    }

    @Test
    public void theCurveNeverTurnsLouderAudioUpMore() {
        double previous = Double.MAX_VALUE;
        for (double level = -100; level <= 10; level += 0.5) {
            double gain = AudioCompressor.gainForLevel(level);
            assertTrue(gain <= previous);
            assertTrue(gain <= AudioCompressor.MAX_GAIN_DB && gain >= AudioCompressor.MIN_GAIN_DB);
            previous = gain;
        }
    }

    @Test
    public void levelIsTheSummedPowerOfTheBandsThatCarryAReading() {
        assertEquals(-25.0, AudioCompressor.levelDb(frame(-25), FLOOR_DB), 0.01);
        float[] silence = new float[BANDS];
        java.util.Arrays.fill(silence, (float) FLOOR_DB);
        assertTrue(Double.isNaN(AudioCompressor.levelDb(silence, FLOOR_DB)));
        assertTrue(Double.isNaN(AudioCompressor.levelDb(null, FLOOR_DB)));
        assertTrue(Double.isNaN(AudioCompressor.levelDb(new float[0], FLOOR_DB)));
    }

    @Test
    public void quietSpeechComesUpAndLoudSpeechComesDown() {
        AudioCompressor quiet = new AudioCompressor(FLOOR_DB);
        assertEquals(AudioCompressor.gainForLevel(-40), settle(quiet, -40, 0, 5000), 0.25);
        assertTrue(quiet.gainDb() > 5);

        AudioCompressor loud = new AudioCompressor(FLOOR_DB);
        assertEquals(AudioCompressor.gainForLevel(-8), settle(loud, -8, 0, 5000), 0.25);
        assertTrue(loud.gainDb() < -5);
    }

    @Test
    public void itsOwnGainIsNotMistakenForTheEpisodeGettingLouder() {
        // the gain is in every frame the spectrum reports; chasing it would spiral or flutter
        AudioCompressor compressor = new AudioCompressor(FLOOR_DB);
        double settled = settle(compressor, -35, 0, 5000);
        for (long time = 5050; time <= 10000; time += 50) {
            assertEquals(settled, compressor.update(frame(-35 + settled), settled, time), 0.0);
        }
    }

    @Test
    public void aLoudPassageIsTurnedDownAtOnceAndComesBackSlowly() {
        AudioCompressor compressor = new AudioCompressor(FLOOR_DB);
        double quietGain = settle(compressor, -35, 0, 5000);

        // two frames (100 ms) into a shout the gain is most of the way down
        double gain = compressor.update(frame(-8 + quietGain), quietGain, 5050);
        gain = compressor.update(frame(-8 + gain), gain, 5100);
        double loudGain = AudioCompressor.gainForLevel(-8);
        assertTrue(gain < loudGain + 1.5);

        // back to quiet: 200 ms later it has barely moved, a few seconds later it is back
        double after200 = settle(compressor, -35, 5150, 200);
        assertTrue(after200 < loudGain + 0.3 * (quietGain - loudGain));
        assertEquals(quietGain, settle(compressor, -35, 5400, 6000), 0.25);
    }

    @Test
    public void aPauseKeepsTheGainOfTheSpeechAroundIt() {
        AudioCompressor compressor = new AudioCompressor(FLOOR_DB);
        double speech = settle(compressor, -40, 0, 3000);
        // room tone the spectrum still reads, then digital silence, then no reading at all:
        // none of it is followed
        assertTrue(AudioCompressor.levelDb(frame(-58 + speech), FLOOR_DB) > -58);
        assertEquals(speech, settle(compressor, -58, 3050, 4000), 0.0);
        float[] silence = new float[BANDS];
        java.util.Arrays.fill(silence, (float) FLOOR_DB);
        assertEquals(speech, compressor.update(silence, speech, 8000), 0.0);
        assertEquals(speech, compressor.update(null, speech, 8050), 0.0);
        assertEquals(speech, compressor.gainDb(), 0.0);
    }

    @Test
    public void gainsComeInQuarterDecibelSteps() {
        AudioCompressor compressor = new AudioCompressor(FLOOR_DB);
        for (double level = -45; level <= -5; level += 3.7) {
            double gain = settle(compressor, level, (long) ((level + 50) * 10000), 4000);
            assertEquals(0.0, Math.IEEEremainder(gain, AudioCompressor.STEP_DB), 1e-9);
        }
    }

    @Test
    public void resetStartsOverAtNoGain() {
        AudioCompressor compressor = new AudioCompressor(FLOOR_DB);
        assertEquals(0.0, compressor.gainDb(), 0.0);
        settle(compressor, -40, 0, 3000);
        compressor.reset();
        assertEquals(0.0, compressor.gainDb(), 0.0);
        // the first frame after a reset sets the level outright instead of gliding to it
        assertEquals(AudioCompressor.gainForLevel(-10), compressor.update(frame(-10), 0, 0), 0.25);
    }
}
