package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What the equalizer bands carry: the volume boost and the audio compressor's gain share them,
 * and their sum has to stay inside what a band accepts.
 */
public class PlaybackEqualizerTest {
    @Test
    public void testTheCompressorGainSitsOnTopOfTheBoost() {
        assertEquals(0.0, PlaybackManager.bandGainDb(0, 0), 0.0);
        assertEquals(6.0, PlaybackManager.bandGainDb(6, 0), 0.0);
        assertEquals(-5.25, PlaybackManager.bandGainDb(0, -5.25), 0.0);
        assertEquals(4.5, PlaybackManager.bandGainDb(6, -1.5), 0.0);
    }

    @Test
    public void testTheSumStaysInsideABandsRange() {
        // a full boost leaves the compressor no room to raise quiet audio further
        assertEquals(12.0, PlaybackManager.bandGainDb(12, 10), 0.0);
        assertEquals(-24.0, PlaybackManager.bandGainDb(0, -40), 0.0);
    }
}
