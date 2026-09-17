package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopFeedOrderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private long addFeedWithEpisodePlayedAt(String title, long playedAt) throws Exception {
        Feed feed = new Feed("http://example.com/" + title + ".xml", null, title);
        database.insertFeed(feed);
        FeedItem item = new FeedItem();
        item.setTitle(title + " episode");
        long itemId = database.insertItem(feed.getId(), item);
        FeedMedia media =
                new FeedMedia(item, "http://example.com/" + title + ".mp3", 1000, "audio/mpeg");
        media.setLastPlayedTimeStatistics(playedAt);
        database.insertMedia(itemId, media);
        return feed.getId();
    }

    @Test
    public void testLastPlayedTimesPerFeed() throws Exception {
        long first = addFeedWithEpisodePlayedAt("Alpha", 1000);
        long second = addFeedWithEpisodePlayedAt("Beta", 2000);
        addFeedWithEpisodePlayedAt("Gamma", 0);

        Map<Long, Long> times = database.getFeedLastPlayedTimes();
        assertEquals(2, times.size());
        assertEquals(Long.valueOf(1000), times.get(first));
        assertEquals(Long.valueOf(2000), times.get(second));
    }

    @Test
    public void testSortByLastPlayedPutsMostRecentFirst() {
        Feed alpha = new Feed("http://example.com/a.xml", null, "Alpha");
        alpha.setId(1);
        Feed beta = new Feed("http://example.com/b.xml", null, "Beta");
        beta.setId(2);
        Feed gamma = new Feed("http://example.com/c.xml", null, "Gamma");
        gamma.setId(3);

        List<Feed> feeds = new ArrayList<>(List.of(beta, alpha, gamma));
        Map<Long, Long> times = new HashMap<>();
        times.put(3L, 5000L);
        times.put(1L, 2000L);

        FeedSorter.sortByLastPlayed(feeds, times);
        assertEquals("Gamma", feeds.get(0).getTitle());
        assertEquals("Alpha", feeds.get(1).getTitle());
        assertEquals("Beta", feeds.get(2).getTitle());
    }
}
