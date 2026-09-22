package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The player, the downloader, the playback cache and feed refresh each hold their own copy of an
 * episode, loaded at different times. Saving one copy must not undo what another has changed.
 */
public class MediaWritesTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private DesktopDatabase database;
    private long feedId;
    private volatile String feedXml;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // one file name for every episode, told apart only by the query, as several hosts do
        server.createContext("/media.mp3", exchange -> {
            byte[] bytes = ("audio of " + exchange.getRequestURI().getQuery())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/feed.xml", exchange -> {
            byte[] bytes = feedXml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Writes Feed");
        database.insertFeed(feed);
        feedId = feed.getId();
    }

    @After
    public void tearDown() throws Exception {
        server.stop(0);
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private FeedMedia episode(String title, String url) throws Exception {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        item.setFeedId(feedId);
        long itemId = database.insertItem(feedId, item);
        FeedMedia media = new FeedMedia(item, url, 0, "audio/mpeg");
        database.insertMedia(itemId, media);
        return media;
    }

    @Test
    public void testSavingPlaybackKeepsADownloadThatFinishedMeanwhile() throws Exception {
        FeedMedia playing = episode("Streamed", baseUrl + "/media.mp3?id=1");
        database.setMediaDownloaded(playing.getId(), "C:\\media\\1.mp3", 1234L, 99L);
        database.setMediaCacheFile(playing.getId(), "C:\\cache\\1.mp3");

        // the player's copy was loaded before either file existed
        playing.setPosition(61000);
        playing.setDuration(3600000);
        playing.setLastPlayedTimeHistory(new Date(5000L));
        database.updatePlaybackState(playing);

        FeedMedia stored = database.getMedia(playing.getId());
        assertEquals(61000, stored.getPosition());
        assertEquals(3600000, stored.getDuration());
        assertEquals(5000L, stored.getLastPlayedTimeHistory().getTime());
        assertEquals("C:\\media\\1.mp3", stored.getLocalFileUrl());
        assertEquals(1234L, stored.getDownloadDate());
        assertEquals("C:\\cache\\1.mp3", stored.getCacheFileUrl());
    }

    @Test
    public void testFinishingADownloadKeepsPositionAndCache() throws Exception {
        FeedMedia media = episode("Downloading", baseUrl + "/media.mp3?id=1");
        FeedMedia listened = database.getMedia(media.getId());
        listened.setPosition(120000);
        listened.setDuration(1800000);
        database.updatePlaybackState(listened);
        database.setMediaCacheFile(media.getId(), "C:\\cache\\1.mp3");

        database.setMediaDownloaded(media.getId(), "C:\\media\\1.mp3", 1234L, 0L);

        FeedMedia stored = database.getMedia(media.getId());
        assertEquals(120000, stored.getPosition());
        assertEquals(1800000, stored.getDuration());
        assertEquals("C:\\cache\\1.mp3", stored.getCacheFileUrl());
        assertEquals("C:\\media\\1.mp3", stored.getLocalFileUrl());
        assertEquals("a size of 0 means unknown and keeps the stored one", 0L, stored.getSize());
    }

    @Test
    public void testClearingADownloadKeepsTheRest() throws Exception {
        FeedMedia media = episode("Delete me", baseUrl + "/media.mp3?id=1");
        media.setPosition(3000);
        database.updatePlaybackState(media);
        database.setMediaDownloaded(media.getId(), "C:\\media\\1.mp3", 1234L, 10L);

        database.clearMediaDownload(media.getId());

        FeedMedia stored = database.getMedia(media.getId());
        assertNull(stored.getLocalFileUrl());
        assertEquals(0L, stored.getDownloadDate());
        assertEquals(3000, stored.getPosition());
    }

    @Test
    public void testFeedRefreshKeepsMeasuredDurationAndFiles() throws Exception {
        FeedMedia media = episode("Refreshed", baseUrl + "/media.mp3?id=1");
        media.setDuration(1799500);
        media.setPosition(42000);
        database.updatePlaybackState(media);
        database.setMediaDownloaded(media.getId(), "C:\\media\\1.mp3", 1234L, 10L);

        FeedMedia fromFeed = new FeedMedia(null, baseUrl + "/media.mp3?id=1&v=2", 2048L, "audio/mp4");
        fromFeed.setId(media.getId());
        fromFeed.setDuration(1800000);
        database.updateMediaFromFeed(fromFeed);

        FeedMedia stored = database.getMedia(media.getId());
        assertEquals(baseUrl + "/media.mp3?id=1&v=2", stored.getDownloadUrl());
        assertEquals("audio/mp4", stored.getMimeType());
        assertEquals(2048L, stored.getSize());
        assertEquals("the player's measured duration wins", 1799500, stored.getDuration());
        assertEquals(42000, stored.getPosition());
        assertEquals("C:\\media\\1.mp3", stored.getLocalFileUrl());
    }

    @Test
    public void testEpisodesWithTheSameFileNameDownloadToSeparateFiles() throws Exception {
        FeedMedia first = episode("First", baseUrl + "/media.mp3?id=1");
        FeedMedia second = episode("Second", baseUrl + "/media.mp3?id=2");
        assertNotEquals(EpisodeDownloader.targetFile(first), EpisodeDownloader.targetFile(second));

        EpisodeDownloader downloader = new EpisodeDownloader(database);
        try {
            File firstFile = download(downloader, first);
            File secondFile = download(downloader, second);

            assertTrue(firstFile.isFile());
            assertTrue(secondFile.isFile());
            assertEquals("audio of id=1", Files.readString(firstFile.toPath()));
            assertEquals("audio of id=2", Files.readString(secondFile.toPath()));
            assertEquals(firstFile.getAbsolutePath(), database.getMedia(first.getId()).getLocalFileUrl());
            assertEquals(secondFile.getAbsolutePath(), database.getMedia(second.getId()).getLocalFileUrl());
        } finally {
            downloader.shutdown();
        }
    }

    private static File download(EpisodeDownloader downloader, FeedMedia media) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<File> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        downloader.enqueue(media, new EpisodeDownloader.ProgressListener() {
            @Override
            public void onProgress(long mediaId, long bytesRead, long totalBytes) {
            }

            @Override
            public void onFinished(long mediaId, File file) {
                result.set(file);
                done.countDown();
            }

            @Override
            public void onError(long mediaId, Exception e) {
                failure.set(e);
                done.countDown();
            }
        });
        assertTrue("download did not finish in time", done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    @Test
    public void testRefreshStoresAnEnclosureAddedToAKnownEpisode() throws Exception {
        feedXml = rss(item("ep-1", "Announcement", null));
        Feed feed = new FeedUpdater(database).subscribe(baseUrl + "/feed.xml");
        FeedItem announced = database.getItemsOfFeed(feed.getId()).get(0);
        assertNull(announced.getMedia());

        feedXml = rss(item("ep-1", "Announcement", baseUrl + "/media.mp3?id=1"));
        new FeedUpdater(database).refresh(feed);

        FeedItem refreshed = database.getItemsOfFeed(feed.getId()).get(0);
        assertNotNull("the enclosure published later was not stored", refreshed.getMedia());
        assertEquals(baseUrl + "/media.mp3?id=1", refreshed.getMedia().getDownloadUrl());
    }

    @Test
    public void testRefreshKeepsADownload() throws Exception {
        feedXml = rss(item("ep-1", "Episode", baseUrl + "/media.mp3?id=1"));
        Feed feed = new FeedUpdater(database).subscribe(baseUrl + "/feed.xml");
        FeedMedia media = database.getItemsOfFeed(feed.getId()).get(0).getMedia();
        database.setMediaDownloaded(media.getId(), "C:\\media\\1.mp3", 1234L, 10L);

        new FeedUpdater(database).refresh(feed);

        FeedMedia stored = database.getMedia(media.getId());
        assertEquals("C:\\media\\1.mp3", stored.getLocalFileUrl());
        assertEquals(1234L, stored.getDownloadDate());
    }

    @Test
    public void testFeedRepeatingAGuidSubscribesWithOneCopy() throws Exception {
        feedXml = rss(item("same", "Newest", baseUrl + "/media.mp3?id=2"),
                item("same", "Older copy", baseUrl + "/media.mp3?id=1"),
                item("other", "Another", baseUrl + "/media.mp3?id=3"));
        Feed feed = new FeedUpdater(database).subscribe(baseUrl + "/feed.xml");

        assertEquals(2, database.getItemsOfFeed(feed.getId()).size());

        // and refreshing the same feed does not trip over the repeat either
        List<FeedItem> added = new FeedUpdater(database).refresh(feed);
        assertTrue(added.isEmpty());
        assertEquals(2, database.getItemsOfFeed(feed.getId()).size());
    }

    @Test
    public void testFailedSubscribeLeavesNothingBehind() throws Exception {
        feedXml = rss(item("ep-1", "Episode", baseUrl + "/media.mp3?id=1"));
        // make storing the episode's media fail halfway through the subscribe
        try (java.sql.Connection side = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + DesktopPreferences.getDatabaseFile().getAbsolutePath());
             java.sql.Statement stmt = side.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_media BEFORE INSERT ON feed_media"
                    + " BEGIN SELECT RAISE(ABORT, 'disk full'); END");
        }
        try {
            new FeedUpdater(database).subscribe(baseUrl + "/feed.xml");
            org.junit.Assert.fail("the insert failure should surface");
        } catch (java.sql.SQLException expected) {
            // fine
        }
        assertNull("a half-stored feed would count as subscribed on the next try",
                database.getFeedByDownloadUrl(baseUrl + "/feed.xml"));
    }

    @Test
    public void testRefreshOfAFeedUnsubscribedMeanwhileStoresNothing() throws Exception {
        feedXml = rss(item("ep-1", "Episode", baseUrl + "/media.mp3?id=1"));
        FeedUpdater updater = new FeedUpdater(database);
        Feed feed = updater.subscribe(baseUrl + "/feed.xml");
        updater.unsubscribe(feed.getId());

        feedXml = rss(item("ep-2", "New", baseUrl + "/media.mp3?id=2"),
                item("ep-1", "Episode", baseUrl + "/media.mp3?id=1"));
        List<FeedItem> added = updater.refresh(feed);

        assertTrue(added.isEmpty());
        assertTrue(database.getItemsOfFeed(feed.getId()).isEmpty());
    }

    private static String rss(String... items) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>"
                + "<title>Writes Feed</title><link>http://example.com</link>"
                + "<description>Test</description>" + String.join("", items) + "</channel></rss>";
    }

    private static String item(String guid, String title, String enclosure) {
        return "<item><guid>" + guid + "</guid><title>" + title + "</title>"
                + "<pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>"
                + (enclosure == null ? "" : "<enclosure url=\"" + enclosure.replace("&", "&amp;")
                        + "\" length=\"100\" type=\"audio/mpeg\"/>")
                + "</item>";
    }
}
