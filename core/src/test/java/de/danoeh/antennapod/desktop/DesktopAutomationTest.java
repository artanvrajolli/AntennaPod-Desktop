package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopAutomationTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private long feedId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Auto Feed");
        database.insertFeed(feed);
        feedId = feed.getId();
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private static FeedItem item(String title, int durationSec) {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        FeedMedia media = new FeedMedia(item, "http://example.com/" + title + ".mp3", 1000, "audio/mpeg");
        media.setDuration(durationSec * 1000);
        item.setMedia(media);
        return item;
    }

    @Test
    public void testPrefsRoundTrip() throws Exception {
        FeedPrefs prefs = database.getFeedPrefs(feedId);
        assertEquals(FeedPrefs.USE_GLOBAL, prefs.autoDownload);
        prefs.speed = 1.5f;
        prefs.autoDownload = FeedPrefs.ON;
        prefs.includeFilter = "news";
        prefs.minDurationSec = 600;
        prefs.sortCode = "oldest";
        database.saveFeedPrefs(prefs);
        FeedPrefs loaded = database.getFeedPrefs(feedId);
        assertEquals(1.5f, loaded.speed, 0.001);
        assertEquals(FeedPrefs.ON, loaded.autoDownload);
        assertEquals("news", loaded.includeFilter);
        assertEquals(600, loaded.minDurationSec);
        assertEquals("oldest", loaded.sortCode);
        assertEquals(1.5f, loaded.effectiveSpeed(1.0f), 0.001);
        assertTrue(loaded.effectiveAutoDownload(false));
    }

    @Test
    public void testAutoDownloadDecisions() {
        FeedPrefs prefs = new FeedPrefs(feedId);
        assertFalse(Automation.shouldAutoDownload(item("News today", 600), prefs, false));
        assertTrue(Automation.shouldAutoDownload(item("News today", 600), prefs, true));

        prefs.autoDownload = FeedPrefs.OFF;
        assertFalse(Automation.shouldAutoDownload(item("News today", 600), prefs, true));

        prefs.autoDownload = FeedPrefs.ON;
        prefs.excludeFilter = "trailer";
        assertFalse(Automation.shouldAutoDownload(item("New Trailer", 600), prefs, true));
        assertTrue(Automation.shouldAutoDownload(item("News today", 600), prefs, true));

        prefs.excludeFilter = "";
        prefs.includeFilter = "news";
        assertTrue(Automation.shouldAutoDownload(item("Morning News", 600), prefs, true));
        assertFalse(Automation.shouldAutoDownload(item("Sports hour", 600), prefs, true));

        prefs.includeFilter = "";
        prefs.minDurationSec = 300;
        assertFalse(Automation.shouldAutoDownload(item("Short update", 60), prefs, true));
        assertTrue(Automation.shouldAutoDownload(item("Long episode", 600), prefs, true));

        FeedItem noMedia = new FeedItem();
        noMedia.setTitle("No media");
        assertFalse(Automation.shouldAutoDownload(noMedia, prefs, true));
    }
}
