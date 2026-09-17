package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.Date;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopHistoryTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private long firstMediaId;
    private long secondMediaId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "History Feed");
        database.insertFeed(feed);
        firstMediaId = addEpisode(feed.getId(), "Episode One", 60000, 600000, 1000);
        secondMediaId = addEpisode(feed.getId(), "Episode Two", 120000, 1200000, 2000);
    }

    private long addEpisode(long feedId, String title, int playedMs, int durationMs, long size)
            throws Exception {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        long itemId = database.insertItem(feedId, item);
        FeedMedia media = new FeedMedia(item, "http://example.com/" + title + ".mp3", size, "audio/mpeg");
        media.setDuration(durationMs);
        media.setPlayedDuration(playedMs);
        media.setDownloaded(true, System.currentTimeMillis());
        return database.insertMedia(itemId, media);
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testHistoryOrderAndClear() throws Exception {
        database.addToPlaybackHistory(firstMediaId, new Date(1000));
        database.addToPlaybackHistory(secondMediaId, new Date(2000));
        List<FeedItem> history = database.getPlaybackHistory(10);
        assertEquals(2, history.size());
        assertEquals("Episode Two", history.get(0).getTitle());
        assertEquals(1, database.getPlaybackHistory(1).size());
        database.clearPlaybackHistory();
        assertTrue(database.getPlaybackHistory(10).isEmpty());
    }

    @Test
    public void testFeedStatistics() throws Exception {
        List<DesktopDatabase.FeedStatistics> stats = database.getFeedStatistics();
        assertEquals(1, stats.size());
        DesktopDatabase.FeedStatistics row = stats.get(0);
        assertEquals("History Feed", row.feedTitle);
        assertEquals(2, row.episodes);
        assertEquals(180000L, row.playedTimeMs);
        assertEquals(1800000L, row.totalTimeMs);
        assertEquals(2, row.downloaded);
        assertEquals(3000L, row.downloadSizeBytes);
        assertEquals(2, row.unplayed);
    }

    @Test
    public void testMonthlyStatistics() throws Exception {
        database.addToPlaybackHistory(firstMediaId, new Date());
        database.addToPlaybackHistory(secondMediaId, new Date());
        List<DesktopDatabase.MonthlyStatistics> months = database.getMonthlyStatistics();
        assertEquals(1, months.size());
        assertEquals(180000L, months.get(0).playedTimeMs);
    }
}
