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
    public void sustainedSilenceActivatesAndSoundRestores() {
        SilenceSkipper skipper = new SilenceSkipper();
        assertFalse(skipper.update(QUIET, 0));
        assertFalse(skipper.update(QUIET, 400));
        assertTrue(skipper.update(QUIET, 600));
        assertTrue(skipper.update(QUIET, 5000));
        assertTrue(skipper.update(LOUD, 5100));
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
}
