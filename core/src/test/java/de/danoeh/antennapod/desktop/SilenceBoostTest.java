package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two guards that keep silence skipping from killing a stream: a lower speed ceiling for
 * anything coming off the network, and giving the speed-up up after the stream runs dry.
 */
public class SilenceBoostTest {
    private static final long NOW = 1_000_000L;

    // ------------------------------------------------------------------ the ceiling

    @Test
    public void testAFileOnDiskIsFastForwardedAtTheFullBoost() {
        assertEquals(4.0f, SilenceBoost.rateFor(1.0f, true), 0.001f);
        assertEquals(6.0f, SilenceBoost.rateFor(1.5f, true), 0.001f);
    }

    @Test
    public void testNothingEverPlaysFasterThanTheHardCeiling() {
        assertEquals(8.0f, SilenceBoost.rateFor(3.0f, true), 0.001f);
    }

    @Test
    public void testAStreamGetsAMuchLowerCeilingThanAFile() {
        // eight times speed asks the network for eight times the bitrate, which is what drained
        // the buffer and stalled the player mid-episode
        assertEquals(3.0f, SilenceBoost.rateFor(1.0f, false), 0.001f);
        assertTrue(SilenceBoost.rateFor(1.0f, false) < SilenceBoost.rateFor(1.0f, true));
    }

    @Test
    public void testSilenceIsNeverPlayedSlowerThanTheEpisodeItself() {
        // a 4x listener on a stream would otherwise be slowed down to 3x by the ceiling
        assertEquals(4.0f, SilenceBoost.rateFor(4.0f, false), 0.001f);
    }

    // ------------------------------------------------------------------ the backoff

    @Test
    public void testTheBoostIsAllowedUntilSomethingGoesWrong() {
        assertTrue(new SilenceBoost().isAllowed(NOW));
    }

    @Test
    public void testAStallGivesTheBoostUpForAWhile() {
        SilenceBoost boost = new SilenceBoost();
        boost.onStalled(NOW);
        assertFalse(boost.isAllowed(NOW));
        assertFalse(boost.isAllowed(NOW + SilenceBoost.FIRST_BACKOFF_MS - 1));
        assertTrue(boost.isAllowed(NOW + SilenceBoost.FIRST_BACKOFF_MS));
    }

    @Test
    public void testOneDryBufferCountsOnceHoweverOftenItIsReported() {
        // the caller polls at 10Hz while the player is stalled, and must not push the wait out
        // by another 30 seconds on every tick
        SilenceBoost boost = new SilenceBoost();
        for (int i = 0; i < 50; i++) {
            boost.onStalled(NOW + i);
        }
        assertTrue(boost.isAllowed(NOW + SilenceBoost.FIRST_BACKOFF_MS));
    }

    @Test
    public void testAStreamThatKeepsStallingIsLeftAloneForLonger() {
        SilenceBoost boost = new SilenceBoost();
        boost.onStalled(NOW);
        boost.onPlaying();
        boost.onStalled(NOW);
        assertFalse(boost.isAllowed(NOW + SilenceBoost.FIRST_BACKOFF_MS));
        assertTrue(boost.isAllowed(NOW + 2 * SilenceBoost.FIRST_BACKOFF_MS));
    }

    @Test
    public void testTheWaitStopsGrowingAtTheCap() {
        SilenceBoost boost = new SilenceBoost();
        for (int i = 0; i < 40; i++) {
            boost.onPlaying();
            boost.onStalled(NOW);
        }
        assertTrue(boost.isAllowed(NOW + SilenceBoost.MAX_BACKOFF_MS));
    }

    @Test
    public void testANewEpisodeStartsWithACleanSlate() {
        SilenceBoost boost = new SilenceBoost();
        boost.onStalled(NOW);
        boost.reset();
        assertTrue(boost.isAllowed(NOW));
    }
}
