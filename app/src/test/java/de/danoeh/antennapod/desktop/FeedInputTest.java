package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedInputTest {
    @Test
    public void testAddressesSubscribe() {
        assertTrue(FeedInput.looksLikeFeedUrl("https://x.com/feed"));
        assertTrue(FeedInput.looksLikeFeedUrl("  http://x.com/feed  "));
        assertTrue(FeedInput.looksLikeFeedUrl("feed://x.com/rss"));
        assertTrue(FeedInput.looksLikeFeedUrl("pcast://x.com/f"));
        assertTrue(FeedInput.looksLikeFeedUrl("PCAST:x.com/f"));
        assertTrue(FeedInput.looksLikeFeedUrl("itpc://x.com/f"));
        assertTrue(FeedInput.looksLikeFeedUrl("example.com/feed.xml"));
    }

    @Test
    public void testEverythingElseSearches() {
        assertFalse(FeedInput.looksLikeFeedUrl("the daily"));
        assertFalse(FeedInput.looksLikeFeedUrl("node.js"));
        assertFalse(FeedInput.looksLikeFeedUrl("example.com"));
        assertFalse(FeedInput.looksLikeFeedUrl("http is fun"));
        assertFalse(FeedInput.looksLikeFeedUrl(""));
        assertFalse(FeedInput.looksLikeFeedUrl("   "));
        assertFalse(FeedInput.looksLikeFeedUrl(null));
    }
}
