package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pressing play on an episode whose saved or synced position lies at or past the end the player
 * can reach used to mark it played and start the next episode in the list. A start the listener
 * asked for now always plays the episode asked for; only a restart after a drop-out may decide the
 * episode is over.
 */
public class PlaybackStartPositionTest {
    private static final int HOUR = 60 * 60 * 1000;

    @Test
    public void testNoPositionStartsAtTheBeginning() {
        assertEquals(0, PlaybackManager.startPositionFor(0, HOUR, false));
        assertEquals(0, PlaybackManager.startPositionFor(-1, HOUR, false));
        assertEquals(0, PlaybackManager.startPositionFor(0, HOUR, true));
    }

    @Test
    public void testAPositionInsideTheEpisodeIsResumed() {
        assertEquals(20 * 60_000, PlaybackManager.startPositionFor(20 * 60_000, HOUR, false));
        assertEquals(20 * 60_000, PlaybackManager.startPositionFor(20 * 60_000, HOUR, true));
    }

    @Test
    public void testASyncedPositionPastTheEndStartsTheEpisodeInsteadOfTheNextOne() {
        // the other device's copy ran longer than the one the player loaded here
        int synced = 62 * 60_000;
        assertEquals(0, PlaybackManager.startPositionFor(synced, HOUR, false));
    }

    @Test
    public void testAPositionInTheLastSecondsStartsTheEpisodeOver() {
        assertEquals(0, PlaybackManager.startPositionFor(HOUR - 500, HOUR, false));
        assertEquals(0, PlaybackManager.startPositionFor(HOUR - PlaybackManager.START_END_MARGIN_MS,
                HOUR, false));
        int justBefore = HOUR - PlaybackManager.START_END_MARGIN_MS - 1;
        assertEquals(justBefore, PlaybackManager.startPositionFor(justBefore, HOUR, false));
    }

    @Test
    public void testARestartThatCannotGetFurtherFinishesTheEpisode() {
        assertEquals(PlaybackManager.START_FINISHED,
                PlaybackManager.startPositionFor(HOUR, HOUR, true));
        assertEquals(PlaybackManager.START_FINISHED,
                PlaybackManager.startPositionFor(HOUR + 60_000, HOUR, true));
        assertEquals(HOUR - 2000, PlaybackManager.startPositionFor(HOUR - 2000, HOUR, true));
    }

    @Test
    public void testAnUnknownDurationTrustsThePosition() {
        assertEquals(30_000, PlaybackManager.startPositionFor(30_000, 0, false));
        assertEquals(30_000, PlaybackManager.startPositionFor(30_000, 0, true));
    }
}
