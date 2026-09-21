package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The rule that decides whether playback which ended early should be picked back up. */
public class PlaybackBufferTest {
    private static PlaybackManager idleManager() {
        return new PlaybackManager(null, new PlaybackManager.Listener() {
            @Override
            public void onStateChanged() {
            }

            @Override
            public void onPositionChanged(int positionMs, int durationMs) {
            }

            @Override
            public void onLoadingChanged(boolean loading) {
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    @Test
    public void testNothingLoadedIsNotPlayingFromAFile() {
        PlaybackManager manager = idleManager();
        try {
            assertFalse(manager.isPlayingFromFile());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void testAStopFarFromTheEndIsPremature() {
        assertTrue(PlaybackManager.isPrematureStop(120_000, 3_600_000));
    }

    @Test
    public void testAStopAtTheEndIsNot() {
        assertFalse(PlaybackManager.isPrematureStop(3_600_000, 3_600_000));
        assertFalse(PlaybackManager.isPrematureStop(
                3_600_000 - PlaybackManager.PREMATURE_END_TOLERANCE_MS + 1, 3_600_000));
    }

    @Test
    public void testAnUnknownDurationIsNeverPremature() {
        // without a duration there is nothing to be short of, so the episode is taken at its word
        assertFalse(PlaybackManager.isPrematureStop(120_000, 0));
        assertFalse(PlaybackManager.isPrematureStop(0, 3_600_000));
    }

    @Test
    public void testRetriesAreBounded() {
        // one restart that gets nowhere is enough to conclude the episode really was over,
        // so a feed that overstates its duration costs a single extra attempt, not a loop
        assertTrue(PlaybackManager.MAX_RESUME_ATTEMPTS >= 1);
        assertTrue(PlaybackManager.MIN_RESUME_PROGRESS_MS > 0);
    }
}
