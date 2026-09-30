package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopEpisodeSearchTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed first = new Feed("http://example.com/a.xml", null, "Tech Show");
        database.insertFeed(first);
        Feed second = new Feed("http://example.com/b.xml", null, "History Show");
        database.insertFeed(second);
        insert(first, "Rust in production", "We talk about memory safety.", 3000);
        insert(first, "Weekly news", "Also: why Rust keeps winning surveys.", 4000);
        insert(second, "The Roman roads", "Engineering of the ancient world.", 2000);
        insert(second, "100% pure history", "Percent signs in titles.", 1000);
    }

    private void insert(Feed feed, String title, String description, long pubDate) throws Exception {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        item.setDescriptionIfLonger(description);
        item.setPubDate(new Date(pubDate));
        long itemId = database.insertItem(feed.getId(), item);
        database.insertMedia(itemId, new FeedMedia(item, "http://example.com/" + itemId + ".mp3", 1, "audio/mpeg"));
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private List<String> titles(String query) throws Exception {
        List<String> titles = new ArrayList<>();
        for (FeedItem item : database.searchItems(query, 50)) {
            titles.add(item.getTitle());
            assertNotNull("results carry their media, to play them", item.getMedia());
        }
        return titles;
    }

    @Test
    public void testTitleMatchesComeBeforeShowNoteMatches() throws Exception {
        assertEquals(java.util.Arrays.asList("Rust in production", "Weekly news"), titles("rust"));
    }

    @Test
    public void testSearchesEverySubscription() throws Exception {
        assertEquals(java.util.Arrays.asList("The Roman roads"), titles("ROMAN"));
        // found by its show notes, in the other subscription
        assertEquals(java.util.Arrays.asList("The Roman roads"), titles("engineering"));
    }

    @Test
    public void testWildcardsMatchLiterally() throws Exception {
        assertEquals(java.util.Arrays.asList("100% pure history"), titles("100%"));
        assertTrue(titles("_").isEmpty());
    }

    @Test
    public void testBlankFindsNothingAndLimitHolds() throws Exception {
        assertTrue(titles("  ").isEmpty());
        assertEquals(1, database.searchItems("e", 1).size());
    }
}
