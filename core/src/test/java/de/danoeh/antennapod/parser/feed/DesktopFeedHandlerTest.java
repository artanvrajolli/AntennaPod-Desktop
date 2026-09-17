package de.danoeh.antennapod.parser.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.net.common.UrlChecker;
import java.io.File;
import java.net.URL;
import java.util.List;
import org.junit.Test;

public class DesktopFeedHandlerTest {
    @Test
    public void testParseSampleRssFeed() throws Exception {
        URL resource = getClass().getClassLoader().getResource("sample-feed.xml");
        assertNotNull(resource);
        Feed feed = new Feed("https://example.com/feed.xml", null);
        feed.setLocalFileUrl(new File(resource.toURI()).getAbsolutePath());

        FeedHandlerResult result = new FeedHandler().parseFeed(feed);
        Feed parsed = result.feed;
        assertEquals("Sample Podcast", parsed.getTitle());
        assertEquals("Jane Doe", parsed.getAuthor());
        assertEquals("https://example.com/cover.jpg", parsed.getImageUrl());

        List<FeedItem> items = parsed.getItems();
        assertNotNull(items);
        assertEquals(2, items.size());
        FeedItem first = items.get(0);
        assertEquals("Episode One", first.getTitle());
        assertNotNull(first.getMedia());
        assertEquals("https://example.com/ep1.mp3", first.getMedia().getDownloadUrl());
        assertTrue(first.getDescription() != null && first.getDescription().contains("First episode"));
    }

    @Test
    public void testUrlChecker() {
        assertEquals("http://example.com/feed.xml", UrlChecker.prepareUrl("example.com/feed.xml"));
        assertEquals("http://example.com/feed.xml", UrlChecker.prepareUrl("feed://example.com/feed.xml"));
        assertTrue(UrlChecker.urlEquals("https://example.com/a?x=1", "https://example.com/a?x=1"));
    }
}
