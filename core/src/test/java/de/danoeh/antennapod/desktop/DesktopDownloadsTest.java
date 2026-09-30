package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopDownloadsTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private long firstMediaId;
    private long secondMediaId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Downloads Feed");
        database.insertFeed(feed);
        firstMediaId = insertEpisode(feed, "Episode One");
        secondMediaId = insertEpisode(feed, "Episode Two");
        insertEpisode(feed, "Never Downloaded");
    }

    private long insertEpisode(Feed feed, String title) throws Exception {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        long itemId = database.insertItem(feed.getId(), item);
        FeedMedia media = new FeedMedia(item, "http://example.com/" + itemId + ".mp3", 1000, "audio/mpeg");
        return database.insertMedia(itemId, media);
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testDownloadedItemsNewestDownloadFirst() throws Exception {
        assertTrue(database.getDownloadedItems().isEmpty());
        database.setMediaDownloaded(firstMediaId, "C:/media/one.mp3", 1_000L, 500);
        database.setMediaDownloaded(secondMediaId, "C:/media/two.mp3", 2_000L, 700);
        List<FeedItem> downloaded = database.getDownloadedItems();
        assertEquals(2, downloaded.size());
        assertEquals("Episode Two", downloaded.get(0).getTitle());
        assertEquals("C:/media/two.mp3", downloaded.get(0).getMedia().getLocalFileUrl());
        assertEquals("Episode One", downloaded.get(1).getTitle());
    }

    @Test
    public void testClearedDownloadLeavesTheList() throws Exception {
        database.setMediaDownloaded(firstMediaId, "C:/media/one.mp3", 1_000L, 500);
        database.clearMediaDownload(firstMediaId);
        assertTrue(database.getDownloadedItems().isEmpty());
    }

    @Test
    public void testItemOfMediaCarriesItsMedia() throws Exception {
        FeedItem item = database.getItemOfMedia(secondMediaId);
        assertEquals("Episode Two", item.getTitle());
        assertEquals(secondMediaId, item.getMedia().getId());
        assertNull(database.getItemOfMedia(9_999L));
    }
}
