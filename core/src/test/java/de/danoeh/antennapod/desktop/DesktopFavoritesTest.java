package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopFavoritesTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private long firstId;
    private long secondId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Fav Feed");
        database.insertFeed(feed);
        FeedItem first = new FeedItem();
        first.setTitle("Episode One");
        firstId = database.insertItem(feed.getId(), first);
        FeedItem second = new FeedItem();
        second.setTitle("Episode Two");
        secondId = database.insertItem(feed.getId(), second);
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testFavoriteRoundTrip() throws Exception {
        assertTrue(database.getFavorites().isEmpty());
        database.setFavorite(firstId, true);
        List<FeedItem> favorites = database.getFavorites();
        assertEquals(1, favorites.size());
        assertEquals("Episode One", favorites.get(0).getTitle());
        assertTrue(favorites.get(0).isTagged(FeedItem.TAG_FAVORITE));
        assertTrue(database.getItem(firstId).isTagged(FeedItem.TAG_FAVORITE));
        assertFalse(database.getItem(secondId).isTagged(FeedItem.TAG_FAVORITE));
        database.setFavorite(firstId, false);
        assertTrue(database.getFavorites().isEmpty());
    }
}
