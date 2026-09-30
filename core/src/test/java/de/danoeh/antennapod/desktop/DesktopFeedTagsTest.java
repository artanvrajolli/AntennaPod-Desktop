package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.SortedSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopFeedTagsTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private long techId;
    private long newsId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        techId = database.insertFeed(new Feed("http://example.com/tech.xml", null, "Tech"));
        newsId = database.insertFeed(new Feed("http://example.com/news.xml", null, "News"));
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testTagsAreCleanedAndReplaced() throws Exception {
        database.setFeedTags(techId, Arrays.asList(" Work ", "commute", "work", "", "  "));
        database.setFeedTags(newsId, Collections.singletonList("Commute"));
        Map<Long, SortedSet<String>> tags = database.getFeedTags();
        assertEquals(Arrays.asList("commute", "Work"), Arrays.asList(tags.get(techId).toArray()));
        assertTrue(tags.get(newsId).contains("commute"));

        database.setFeedTags(techId, Collections.emptyList());
        assertFalse(database.getFeedTags().containsKey(techId));
    }

    @Test
    public void testUnsubscribingDropsTheTags() throws Exception {
        database.setFeedTags(techId, Collections.singletonList("Work"));
        database.deleteFeed(techId);
        assertTrue(database.getFeedTags().isEmpty());
    }
}
