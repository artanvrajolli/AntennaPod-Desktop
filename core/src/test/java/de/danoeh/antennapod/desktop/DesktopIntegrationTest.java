package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopIntegrationTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private DesktopDatabase database;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        byte[] feedXml = Files.readAllBytes(
                new File(getClass().getClassLoader().getResource("sample-feed.xml").toURI()).toPath());
        String feed = new String(feedXml, StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] mutableBase = new String[1];
        server.createContext("/feed.xml", exchange -> {
            String body = feed.replace("https://example.com/", mutableBase[0] + "/");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/ep1.mp3", exchange -> {
            byte[] bytes = "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/ep2.m4a", exchange -> {
            byte[] bytes = "fake-m4a-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "audio/mp4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        mutableBase[0] = "http://127.0.0.1:" + server.getAddress().getPort();
        baseUrl = mutableBase[0];
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (database != null) {
            database.close();
        }
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testSubscribeRefreshAndDownload() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        Feed feed = updater.subscribe(baseUrl + "/feed.xml");
        assertNotNull(feed);
        assertEquals("Sample Podcast", feed.getTitle());

        List<Feed> all = database.getAllFeeds();
        assertEquals(1, all.size());

        Feed stored = database.getFeed(feed.getId());
        assertEquals(2, stored.getItems().size());
        FeedItem first = stored.getItems().get(0);
        assertNotNull(first.getMedia());
        assertTrue(first.getMedia().getDownloadUrl().startsWith(baseUrl));
        assertTrue(first.isNew());

        java.util.List<FeedItem> added = updater.refresh(stored);
        assertEquals(0, added.size());
        assertEquals(2, database.getItemsOfFeed(feed.getId()).size());

        EpisodeDownloader downloader = new EpisodeDownloader(database);
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<File> downloaded = new AtomicReference<>();
            AtomicReference<Exception> error = new AtomicReference<>();
            downloader.enqueue(first.getMedia(), new EpisodeDownloader.ProgressListener() {
                @Override
                public void onProgress(long mediaId, long bytesRead, long totalBytes) {
                }

                @Override
                public void onFinished(long mediaId, File file) {
                    downloaded.set(file);
                    latch.countDown();
                }

                @Override
                public void onError(long mediaId, Exception e) {
                    error.set(e);
                    latch.countDown();
                }
            });
            assertTrue(latch.await(30, TimeUnit.SECONDS));
            assertTrue("Download error: " + error.get(), error.get() == null);
            assertNotNull(downloaded.get());
            assertTrue(downloaded.get().exists());
            assertTrue(database.getMedia(first.getMedia().getId()).localFileAvailable());
        } finally {
            downloader.shutdown();
        }
    }
}
