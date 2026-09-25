package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopNewCountTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private Feed feed;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        feed = new Feed("http://example.com/feed.xml", null, "New Feed");
        database.insertFeed(feed);
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testCountNewOnlyCountsUnseenEpisodes() throws Exception {
        FeedItem unseen = new FeedItem();
        unseen.setTitle("Unseen Episode");
        unseen.setNew();
        long unseenId = database.insertItem(feed.getId(), unseen);

        FeedItem unplayed = new FeedItem();
        unplayed.setTitle("Unplayed Episode");
        database.insertItem(feed.getId(), unplayed);

        assertEquals(1, database.countNew(feed.getId()));
        assertEquals(2, database.countUnplayed(feed.getId()));

        database.setItemState(unseenId, FeedItem.PLAYED);
        assertEquals(0, database.countNew(feed.getId()));
        assertEquals(1, database.countUnplayed(feed.getId()));
    }

    @Test
    public void testCountsAreScopedToFeed() throws Exception {
        Feed other = new Feed("http://example.com/other.xml", null, "Other Feed");
        database.insertFeed(other);
        FeedItem item = new FeedItem();
        item.setTitle("Other Episode");
        item.setNew();
        database.insertItem(other.getId(), item);

        assertEquals(0, database.countNew(feed.getId()));
        assertEquals(1, database.countNew(other.getId()));
    }

    @Test
    public void testClearNewFlagsMarksSeenButKeepsPlayed() throws Exception {
        FeedItem unseen = new FeedItem();
        unseen.setTitle("Unseen Episode");
        unseen.setNew();
        database.insertItem(feed.getId(), unseen);

        FeedItem alsoUnseen = new FeedItem();
        alsoUnseen.setTitle("Also Unseen");
        alsoUnseen.setNew();
        database.insertItem(feed.getId(), alsoUnseen);

        FeedItem played = new FeedItem();
        played.setTitle("Played Episode");
        played.setPlayed(true);
        database.insertItem(feed.getId(), played);

        assertEquals(2, database.clearNewFlags(feed.getId()));
        assertEquals(0, database.countNew(feed.getId()));
        assertEquals(2, database.countUnplayed(feed.getId()));
        for (FeedItem item : database.getItemsOfFeed(feed.getId())) {
            assertEquals(false, item.isNew());
        }

        assertEquals("nothing left to clear", 0, database.clearNewFlags(feed.getId()));
    }

    @Test
    public void testClearAllNewFlagsAcrossFeeds() throws Exception {
        Feed other = new Feed("http://example.com/other.xml", null, "Other Feed");
        database.insertFeed(other);

        FeedItem first = new FeedItem();
        first.setTitle("First Unseen");
        first.setNew();
        database.insertItem(feed.getId(), first);

        FeedItem second = new FeedItem();
        second.setTitle("Second Unseen");
        second.setNew();
        database.insertItem(other.getId(), second);

        FeedItem played = new FeedItem();
        played.setTitle("Played Episode");
        played.setPlayed(true);
        database.insertItem(other.getId(), played);

        assertEquals(2, database.clearAllNewFlags());
        assertEquals(0, database.countNew(feed.getId()));
        assertEquals(0, database.countNew(other.getId()));
        assertEquals(1, database.countUnplayed(feed.getId()));
        assertEquals(1, database.countUnplayed(other.getId()));

        assertEquals("nothing left to clear", 0, database.clearAllNewFlags());
    }
}
