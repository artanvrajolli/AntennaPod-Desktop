package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SilenceSkipperTest {
    private static final float[] QUIET = {-70f, -80f, -90f};
    private static final float[] LOUD = {-20f, -30f, -25f};

    @Test
    public void loudAudioIsNeverSilent() {
        SilenceSkipper skipper = new SilenceSkipper();
        for (long time = 0; time <= 5000; time += 100) {
            assertFalse(skipper.update(LOUD, time));
        }
    }

    @Test
    public void shortQuietStretchIsNotSkipped() {
        SilenceSkipper skipper = new SilenceSkipper();
        assertFalse(skipper.update(QUIET, 0));
        assertFalse(skipper.update(QUIET, 300));
        assertFalse(skipper.update(LOUD, 400));
    }

    @Test
    public void wordGapsNeverTriggerSkipping() {
        // a 400 ms pause between words must not be fast-forwarded
        SilenceSkipper skipper = new SilenceSkipper();
        assertFalse(skipper.update(LOUD, 0));
        assertFalse(skipper.update(QUIET, 100));
        assertFalse(skipper.update(QUIET, 400));
        assertFalse(skipper.update(LOUD, 500));
    }

    @Test
    public void sustainedSilenceActivatesAndSoundRestores() {
        SilenceSkipper skipper = new SilenceSkipper();
        assertFalse(skipper.update(QUIET, 0));
        assertFalse(skipper.update(QUIET, 400));
        assertTrue(skipper.update(QUIET, 600));
        assertTrue(skipper.update(QUIET, 5000));
        // the hysteresis verdict still holds on the very first loud frame, but the instant
        // loud flag is what drops the fast-forward so speech is never sped through
        assertTrue(skipper.update(LOUD, 5100));
        assertTrue(skipper.isAudioLoud());
        assertFalse(skipper.update(LOUD, 5400));
    }

    @Test
    public void missingSpectrumDataKeepsCurrentState() {
        SilenceSkipper skipper = new SilenceSkipper();
        assertFalse(skipper.update(null, 0));
        assertFalse(skipper.update(new float[0], 100));
        assertFalse(skipper.update(QUIET, 200));
        assertTrue(skipper.update(QUIET, 800));
        assertTrue(skipper.update(null, 3000));
        assertTrue(skipper.update(new float[0], 3100));
    }

    @Test
    public void resetClearsSilence() {
        SilenceSkipper skipper = new SilenceSkipper();
        skipper.update(QUIET, 0);
        assertTrue(skipper.update(QUIET, 600));
        skipper.reset();
        assertFalse(skipper.update(QUIET, 700));
    }

    @Test
    public void loudnessReflectsLatestFrameWithoutHysteresis() {
        SilenceSkipper skipper = new SilenceSkipper();
        skipper.update(QUIET, 0);
        assertFalse(skipper.isAudioLoud());
        // a single loud frame is reported immediately, even though the silence verdict
        // waits for the exit delay before flipping
        assertFalse(skipper.update(LOUD, 100));
        assertTrue(skipper.isAudioLoud());
        assertFalse(skipper.update(QUIET, 200));
        assertFalse(skipper.isAudioLoud());
    }

    @Test
    public void singleHissingBandDoesNotBlockSilenceDetection() {
        // narrowband hiss on one of 128 bands: the old loudest-band check read this as
        // speech and never skipped; average power still reads it as silence
        float[] hiss = new float[128];
        java.util.Arrays.fill(hiss, -70f);
        hiss[60] = -20f;
        SilenceSkipper skipper = new SilenceSkipper();
        boolean silent = false;
        for (long time = 0; time <= 1000; time += 100) {
            silent = skipper.update(hiss, time);
        }
        assertTrue(silent);
    }

    @Test
    public void speechAboveTheNoiseFloorIsNeverSkipped() {
        // quiet room, clearly audible speech: must play at normal speed throughout.
        // (mirrors the player: the boost is the verdict minus the instant loud flag)
        float[] roomTone = {-65f, -65f, -65f};
        float[] softSpeech = {-42f, -42f, -42f};
        SilenceSkipper skipper = new SilenceSkipper();
        for (long time = 0; time <= 9000; time += 100) {
            skipper.update(roomTone, time);
        }
        assertTrue(skipper.update(roomTone, 9100)); // room tone itself is silence: skipped
        for (long time = 9200; time <= 12200; time += 100) {
            boolean boosted = skipper.update(softSpeech, time) && !skipper.isAudioLoud();
            assertFalse(boosted);
        }
    }

    @Test
    public void duckedSpeechStillReadsAsLoud() {
        // the player ducks the volume while fast-forwarding and the spectrum reflects it:
        // speech at -40 dB observed at -56.5 dB through a 0.15 duck must still read as loud,
        // or the duck latches silence on through whole spoken passages
        float[] roomTone = {-70f, -70f, -70f};
        float[] duckedSpeech = {-56.5f, -56.5f, -56.5f};
        SilenceSkipper compensated = new SilenceSkipper();
        for (long time = 0; time <= 1500; time += 100) {
            compensated.update(roomTone, time);
        }
        for (long time = 1600; time <= 3600; time += 100) {
            // verdict may still hold for the exit delay, but the instant loud flag is
            // what the player uses to drop the boost, so assert the effective boost
            boolean boosted = compensated.update(duckedSpeech, time, 0.15)
                    && !compensated.isAudioLoud();
            assertFalse(boosted);
        }
        assertTrue(compensated.isAudioLoud());

        // same frames without compensation read as silence — the old behaviour
        SilenceSkipper uncompensated = new SilenceSkipper();
        for (long time = 0; time <= 1500; time += 100) {
            uncompensated.update(roomTone, time);
        }
        uncompensated.update(duckedSpeech, 1600);
        assertFalse(uncompensated.isAudioLoud());
    }

    @Test
    public void thresholdAdaptsToANoisyRoom() {
        SilenceSkipper skipper = new SilenceSkipper();
        float[] roomNoise = {-38f, -38f, -38f};
        for (long time = 0; time <= 10000; time += 100) {
            skipper.update(roomNoise, time);
        }
        // the floor followed the room up, so the threshold moved with it
        assertTrue(skipper.thresholdDb() > SilenceSkipper.SILENCE_THRESHOLD_DB);
    }

    @Test
    public void digitalSilenceBecomesCompletelySilent() {
        float[] digital = {-70f, -70f, -70f};
        SilenceSkipper skipper = new SilenceSkipper();
        assertFalse(skipper.isCompletelySilent());
        skipper.update(digital, 0);
        assertFalse(skipper.isCompletelySilent());
        skipper.update(digital, 300);
        assertFalse(skipper.isCompletelySilent());
        skipper.update(digital, 400);
        assertTrue(skipper.isCompletelySilent());
    }

    @Test
    public void roomToneAndSpeechAreNeverCompleteSilence() {
        // the strict fixed threshold only fires on (near-)digital silence: audible room
        // tone and any speech must never trigger a skip, however long they last
        float[] roomTone = {-50f, -50f, -50f};
        float[] speech = {-30f, -25f, -35f};
        SilenceSkipper skipper = new SilenceSkipper();
        for (long time = 0; time <= 5000; time += 100) {
            skipper.update(roomTone, time);
            assertFalse(skipper.isCompletelySilent());
        }
        for (long time = 5100; time <= 10000; time += 100) {
            skipper.update(speech, time);
            assertFalse(skipper.isCompletelySilent());
        }
    }

    @Test
    public void soundEndsCompleteSilenceImmediately() {
        float[] digital = {-70f, -70f, -70f};
        float[] speech = {-30f, -25f, -35f};
        SilenceSkipper skipper = new SilenceSkipper();
        skipper.update(digital, 0);
        skipper.update(digital, 400);
        assertTrue(skipper.isCompletelySilent());
        skipper.update(speech, 450);
        assertFalse(skipper.isCompletelySilent());
    }

    @Test
    public void missingDataKeepsCompleteSilenceState() {
        float[] digital = {-70f, -70f, -70f};
        SilenceSkipper skipper = new SilenceSkipper();
        skipper.update(digital, 0);
        skipper.update(digital, 400);
        assertTrue(skipper.isCompletelySilent());
        skipper.update(null, 5000);
        assertTrue(skipper.isCompletelySilent());
        skipper.update(new float[0], 5100);
        assertTrue(skipper.isCompletelySilent());
    }

    @Test
    public void resetClearsCompleteSilence() {
        float[] digital = {-70f, -70f, -70f};
        SilenceSkipper skipper = new SilenceSkipper();
        skipper.update(digital, 0);
        skipper.update(digital, 400);
        assertTrue(skipper.isCompletelySilent());
        skipper.reset();
        assertFalse(skipper.isCompletelySilent());
        skipper.update(digital, 500);
        assertFalse(skipper.isCompletelySilent());
    }
}
