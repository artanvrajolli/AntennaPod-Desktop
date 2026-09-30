package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Backups never touch the real settings here: they are passed in and captured explicitly. */
public class ProfileBackupTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DesktopDatabase database;
    private File zip;
    private long favoriteId;
    private long mediaId;
    private long feedId;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.newFolder("source").getAbsolutePath());
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Backed Up Feed");
        feedId = database.insertFeed(feed);
        FeedItem item = new FeedItem();
        item.setTitle("Favorite Episode");
        favoriteId = database.insertItem(feedId, item);
        database.setFavorite(favoriteId, true);
        mediaId = database.insertMedia(favoriteId,
                new FeedMedia(item, "http://example.com/fav.mp3", 1000, "audio/mpeg"));
        database.setFeedCredentials(feedId, new FeedCredentials.Login("me", "pw"));
        zip = new File(tempFolder.getRoot(), "backup.zip");
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private static Properties settings(String key, String value) {
        Properties values = new Properties();
        values.setProperty(key, value);
        return values;
    }

    @Test
    public void testRoundTripReplacesTheLibraryAndSettings() throws Exception {
        ProfileBackup.write(database, zip, "0.3.2", settings("themeMode", "dark"));

        File target = tempFolder.newFolder("target");
        try (DesktopDatabase other = new DesktopDatabase(new File(target, "antennapod.db"))) {
            other.insertFeed(new Feed("http://example.com/other.xml", null, "Replaced Feed"));
        }
        ProfileBackup.Info info = ProfileBackup.stage(zip, target);
        assertEquals("0.3.2", info.appVersion);
        assertEquals(1, info.feedCount);
        assertTrue(info.createdMs > 0);
        assertTrue(ProfileBackup.hasPending(target));

        Properties imported = new Properties();
        assertTrue(ProfileBackup.applyPending(target, imported::putAll));
        assertEquals("dark", imported.getProperty("themeMode"));
        assertFalse(ProfileBackup.hasPending(target));
        assertTrue("the replaced library is kept", new File(target, ProfileBackup.REPLACED_DB).isFile());

        try (DesktopDatabase restored = new DesktopDatabase(new File(target, "antennapod.db"))) {
            assertEquals(1, restored.getAllFeeds().size());
            assertEquals("Backed Up Feed", restored.getAllFeeds().get(0).getTitle());
            assertEquals("Favorite Episode", restored.getFavorites().get(0).getTitle());
            assertEquals("pw", restored.getFeedCredentials(restored.getAllFeeds().get(0).getId()).password);
        }
        assertFalse("nothing left to apply", ProfileBackup.applyPending(target, imported::putAll));
    }

    @Test
    public void testSomethingElseIsRefusedAndNothingIsStaged() throws Exception {
        File target = tempFolder.newFolder("target");
        File notZip = tempFolder.newFile("notes.zip");
        Files.write(notZip.toPath(), "hello".getBytes());
        expectRefused(notZip, target);

        File emptyZip = new File(tempFolder.getRoot(), "empty.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(emptyZip.toPath()))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("not a backup".getBytes());
            out.closeEntry();
        }
        expectRefused(emptyZip, target);

        File brokenDb = new File(tempFolder.getRoot(), "broken.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(brokenDb.toPath()))) {
            out.putNextEntry(new ZipEntry(ProfileBackup.DB_ENTRY));
            out.write("this is not sqlite".getBytes());
            out.closeEntry();
        }
        expectRefused(brokenDb, target);
    }

    private static void expectRefused(File zip, File target) {
        try {
            ProfileBackup.stage(zip, target);
            fail("staged " + zip.getName());
        } catch (IOException e) {
            assertFalse(ProfileBackup.hasPending(target));
            assertFalse(new File(target, ProfileBackup.STAGED_DB + ".tmp").exists());
        }
    }

    @Test
    public void testCancelDropsAStagedRestore() throws Exception {
        ProfileBackup.write(database, zip, "0.3.2", new Properties());
        File target = tempFolder.newFolder("target");
        ProfileBackup.stage(zip, target);
        ProfileBackup.cancelPending(target);
        assertFalse(ProfileBackup.hasPending(target));
    }

    @Test
    public void testDownloadsMissingOnThisMachineAreForgotten() throws Exception {
        File present = tempFolder.newFile("here.mp3");
        database.setMediaDownloaded(mediaId, "Z:/elsewhere/gone.mp3", 1_000L, 10);
        assertEquals(1, database.clearMissingDownloads());
        assertTrue(database.getDownloadedItems().isEmpty());
        database.setMediaDownloaded(mediaId, present.getAbsolutePath(), 1_000L, 10);
        assertEquals(0, database.clearMissingDownloads());
        assertEquals(1, database.getDownloadedItems().size());
    }
}
