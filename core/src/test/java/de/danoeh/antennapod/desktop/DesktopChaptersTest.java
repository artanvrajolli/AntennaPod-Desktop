package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopChaptersTest {
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
        server.start();
        mutableBase[0] = "http://127.0.0.1:" + server.getAddress().getPort();
        baseUrl = mutableBase[0];
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
    }

    @After
    public void tearDown() throws Exception {
        server.stop(0);
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testChaptersParsedAndPersisted() throws Exception {
        Feed feed = new FeedUpdater(database).subscribe(baseUrl + "/feed.xml");
        FeedItem episodeOne = null;
        for (FeedItem item : database.getItemsOfFeed(feed.getId())) {
            if ("Episode One".equals(item.getTitle())) {
                episodeOne = item;
            }
        }
        assertNotNull(episodeOne);
        List<Chapter> chapters = episodeOne.getChapters();
        assertNotNull(chapters);
        assertEquals(2, chapters.size());
        assertEquals("Intro", chapters.get(0).getTitle());
        assertEquals(0, chapters.get(0).getStart());
        assertEquals("Main part", chapters.get(1).getTitle());
        assertEquals(600000, chapters.get(1).getStart());

        List<Chapter> reloaded = database.getChapters(episodeOne.getId());
        assertEquals(2, reloaded.size());
        assertEquals("Main part", reloaded.get(1).getTitle());

        FeedItem reloadedItem = database.getItem(episodeOne.getId());
        assertNotNull(reloadedItem.getChapters());
        assertEquals(2, reloadedItem.getChapters().size());
    }
}
