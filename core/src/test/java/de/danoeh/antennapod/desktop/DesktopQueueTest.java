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

public class DesktopQueueTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private long firstId;
    private long secondId;
    private long thirdId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Queue Feed");
        database.insertFeed(feed);
        firstId = database.insertItem(feed.getId(), item("Episode One"));
        secondId = database.insertItem(feed.getId(), item("Episode Two"));
        thirdId = database.insertItem(feed.getId(), item("Episode Three"));
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private static FeedItem item(String title) {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        return item;
    }

    private static List<String> titles(List<FeedItem> items) {
        return items.stream().map(FeedItem::getTitle).collect(java.util.stream.Collectors.toList());
    }

    @Test
    public void testAddKeepsOrderAndIgnoresDuplicates() throws Exception {
        database.addToQueue(firstId);
        database.addToQueue(secondId);
        database.addToQueue(firstId);
        assertEquals(java.util.Arrays.asList("Episode One", "Episode Two"), titles(database.getQueue()));
        assertTrue(database.isInQueue(firstId));
        assertFalse(database.isInQueue(thirdId));
    }

    @Test
    public void testMoveAndRemove() throws Exception {
        database.addToQueue(firstId);
        database.addToQueue(secondId);
        database.addToQueue(thirdId);
        database.moveQueueItem(thirdId, true);
        assertEquals(java.util.Arrays.asList("Episode One", "Episode Three", "Episode Two"),
                titles(database.getQueue()));
        database.moveQueueItem(firstId, true);
        assertEquals(java.util.Arrays.asList("Episode One", "Episode Three", "Episode Two"),
                titles(database.getQueue()));
        database.removeFromQueue(thirdId);
        assertEquals(java.util.Arrays.asList("Episode One", "Episode Two"), titles(database.getQueue()));
        database.clearQueue();
        assertTrue(database.getQueue().isEmpty());
    }

    @Test
    public void testQueueSurvivesRestart() throws Exception {
        database.addToQueue(secondId);
        database.addToQueue(firstId);
        database.close();
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        assertEquals(java.util.Arrays.asList("Episode Two", "Episode One"), titles(database.getQueue()));
    }
}
