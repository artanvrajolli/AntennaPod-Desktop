package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javafx.util.Duration;
import org.junit.Test;

/** Covers the decisions behind resuming playback that ended before the episode did. */
public class PlaybackRecoveryTest {
    @Test
    public void testStopWellBeforeTheEndIsPremature() {
        assertTrue(PlaybackManager.isPrematureStop(600_000, 3_600_000));
    }

    @Test
    public void testStopAtTheEndIsNotPremature() {
        assertFalse(PlaybackManager.isPrematureStop(3_600_000, 3_600_000));
    }

    @Test
    public void testStopInsideTheToleranceIsNotPremature() {
        int duration = 3_600_000;
        int position = duration - PlaybackManager.PREMATURE_END_TOLERANCE_MS + 1;
        assertFalse(PlaybackManager.isPrematureStop(position, duration));
    }

    @Test
    public void testUnknownDurationIsNeverPremature() {
        assertFalse(PlaybackManager.isPrematureStop(600_000, 0));
        assertFalse(PlaybackManager.isPrematureStop(0, 3_600_000));
    }

    @Test
    public void testDurationFallsBackToTheStoredValue() {
        assertEquals(1_800_000, PlaybackManager.resolveDuration(null, 1_800_000));
        assertEquals(1_800_000,
                PlaybackManager.resolveDuration(Duration.UNKNOWN, 1_800_000));
        assertEquals(900_000,
                PlaybackManager.resolveDuration(Duration.millis(900_000), 1_800_000));
    }
}
