package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A folder of the user's own files as a subscription; above all, the files are never deleted. */
public class LocalFolderFeedsTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private FeedUpdater updater;
    private File music;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.newFolder("data").getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        updater = new FeedUpdater(database);
        music = tempFolder.newFolder("My Audiobooks");
        write(new File(music, "01 Chapter One.mp3"));
        new File(music, "Season 2").mkdir();
        write(new File(music, "Season 2/02 Chapter Two.M4A"));
        write(new File(music, "notes.txt"));
        write(new File(music, "cover.jpg"));
    }

    private static void write(File file) throws Exception {
        Files.write(file.toPath(), new byte[]{1, 2, 3});
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private static List<String> titles(Feed feed) {
        List<String> titles = new ArrayList<>();
        for (FeedItem item : feed.getItems()) {
            titles.add(item.getTitle());
        }
        Collections.sort(titles);
        return titles;
    }

    @Test
    public void testFolderBecomesAPodcastOfItsPlayableFiles() throws Exception {
        Feed feed = updater.subscribeLocalFolder(music);
        assertTrue(feed.isLocalFeed());
        assertEquals("My Audiobooks", feed.getTitle());
        assertEquals(new File(music, "cover.jpg").toURI().toString(), feed.getImageUrl());
        assertEquals(java.util.Arrays.asList("01 Chapter One", "02 Chapter Two"), titles(feed));
        for (FeedItem item : feed.getItems()) {
            assertTrue("plays from the file", item.getMedia().localFileAvailable());
            assertTrue(new File(item.getMedia().getLocalFileUrl()).isFile());
            assertTrue(LocalFolderFeeds.isLocalMedia(item.getMedia()));
        }
        assertEquals("adding it again is the same subscription",
                feed.getId(), updater.subscribeLocalFolder(music).getId());
        assertTrue("not listed as downloads", database.getDownloadedItems().isEmpty());
    }

    @Test
    public void testRefreshFindsNewFilesAndDropsGoneOnesWithoutTouchingDisk() throws Exception {
        Feed feed = updater.subscribeLocalFolder(music);
        write(new File(music, "03 Chapter Three.mp3"));
        File gone = new File(music, "01 Chapter One.mp3");
        assertTrue(gone.delete());

        List<FeedItem> added = updater.refresh(database.getFeed(feed.getId()));
        assertEquals(1, added.size());
        assertEquals("03 Chapter Three", added.get(0).getTitle());
        assertEquals(java.util.Arrays.asList("02 Chapter Two", "03 Chapter Three"),
                titles(database.getFeed(feed.getId())));
        assertTrue(new File(music, "Season 2/02 Chapter Two.M4A").isFile());
    }

    @Test
    public void testUnsubscribingNeverDeletesTheUsersFiles() throws Exception {
        Feed feed = updater.subscribeLocalFolder(music);
        updater.unsubscribe(feed.getId());
        assertTrue(database.getAllFeeds().isEmpty());
        assertTrue(new File(music, "01 Chapter One.mp3").isFile());
        assertTrue(new File(music, "Season 2/02 Chapter Two.M4A").isFile());
        assertTrue(new File(music, "notes.txt").isFile());
    }

    @Test
    public void testOnlyTheAppsOwnFoldersAreDeletable() throws Exception {
        File media = DesktopPreferences.getMediaDir();
        File download = new File(media, "7/12-episode.mp3");
        assertTrue(LocalFolderFeeds.isAppOwned(download.getPath()));
        assertTrue(LocalFolderFeeds.isAppOwned(
                new File(DesktopPreferences.getEpisodeCacheDir(), "7/3-x.mp3").getPath()));
        assertFalse(LocalFolderFeeds.isAppOwned(new File(music, "01 Chapter One.mp3").getPath()));
        assertFalse("climbing out of the media folder",
                LocalFolderFeeds.isAppOwned(new File(media, "../../My Audiobooks/x.mp3").getPath()));
        assertFalse("the folder itself", LocalFolderFeeds.isAppOwned(media.getPath()));
        assertFalse(LocalFolderFeeds.isAppOwned(null));
        assertFalse(LocalFolderFeeds.isAppOwned(""));
    }

    @Test
    public void testLocalFoldersStayOutOfOpml() throws Exception {
        updater.subscribeLocalFolder(music);
        database.insertFeed(new Feed("http://example.com/feed.xml", null, "Remote Show"));
        StringWriter out = new StringWriter();
        new OpmlImporter(database, updater).exportToWriter(out);
        assertTrue(out.toString().contains("http://example.com/feed.xml"));
        assertFalse(out.toString().contains("antennapod_local:"));
    }

    @Test
    public void testFolderUrlRoundTrip() {
        Feed feed = new Feed(LocalFolderFeeds.feedUrlFor(music), null, "x");
        assertEquals(music.getAbsoluteFile(), LocalFolderFeeds.folderOf(feed));
        assertNull(LocalFolderFeeds.folderOf(new Feed("http://example.com/feed.xml", null, "y")));
        assertNotNull(LocalFolderFeeds.mimeOf("a.mp3"));
    }
}
