package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@link FeedUpdater#preview} backs the podcast details modal and must not store anything. */
public class DesktopFeedPreviewTest {
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
        server.createContext("/feed.xml", exchange -> {
            byte[] bytes = feed.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/missing.xml", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
    }

    @After
    public void tearDown() throws Exception {
        server.stop(0);
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testPreviewReturnsWhatTheDetailsModalShows() throws Exception {
        Feed feed = new FeedUpdater(database).preview(baseUrl + "/feed.xml");
        assertEquals("Sample Podcast", feed.getTitle());
        assertEquals("A sample podcast for testing", feed.getDescription());
        assertEquals("https://example.com/podcast", feed.getLink());
        assertEquals("en", feed.getLanguage());
        assertEquals("Jane Doe", feed.getAuthor());
        assertEquals(baseUrl + "/feed.xml", feed.getDownloadUrl());
        assertFalse(feed.getItems().isEmpty());
    }

    @Test
    public void testPreviewStoresNothing() throws Exception {
        new FeedUpdater(database).preview(baseUrl + "/feed.xml");
        assertTrue("previewing a podcast must not subscribe to it",
                database.getAllFeeds().isEmpty());
    }

    @Test
    public void testSubscribingAfterAPreviewStillWorks() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        updater.preview(baseUrl + "/feed.xml");
        Feed subscribed = updater.subscribe(baseUrl + "/feed.xml");
        assertEquals("Sample Podcast", subscribed.getTitle());
        assertEquals(1, database.getAllFeeds().size());
    }

    @Test(expected = Exception.class)
    public void testPreviewOfAFeedThatIsNotThereFails() throws Exception {
        new FeedUpdater(database).preview(baseUrl + "/missing.xml");
    }
}
