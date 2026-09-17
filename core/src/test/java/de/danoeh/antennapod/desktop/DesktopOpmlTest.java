package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.importexport.OpmlElement;
import de.danoeh.antennapod.storage.importexport.OpmlReader;
import de.danoeh.antennapod.storage.importexport.OpmlWriter;
import java.io.File;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopOpmlTest {
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
    public void testWriteReadRoundTrip() throws Exception {
        List<Feed> feeds = new ArrayList<>();
        feeds.add(new Feed(baseUrl + "/feed.xml", null, "Sample & Cast <Show>"));
        Feed archived = new Feed(baseUrl + "/old.xml", null, "Old Show");
        archived.setState(Feed.STATE_ARCHIVED);
        feeds.add(archived);
        StringWriter writer = new StringWriter();
        OpmlWriter.writeDocument(feeds, writer);
        String opml = writer.toString();
        assertTrue(opml.contains("&amp;"));

        ArrayList<OpmlElement> elements = new OpmlReader().readDocument(new StringReader(opml));
        assertEquals(1, elements.size());
        assertEquals("Sample & Cast <Show>", elements.get(0).getText());
        assertEquals(baseUrl + "/feed.xml", elements.get(0).getXmlUrl());
    }

    @Test
    public void testImportSubscribesFeeds() throws Exception {
        String opml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<opml version=\"2.0\"><head><title>Subs</title></head><body>"
                + "<outline text=\"Sample\" title=\"Sample\" type=\"rss\""
                + " xmlUrl=\"" + baseUrl + "/feed.xml\"/>"
                + "<outline text=\"Broken\" xmlUrl=\"" + baseUrl + "/missing.xml\"/>"
                + "</body></opml>";
        OpmlImporter importer = new OpmlImporter(database, new FeedUpdater(database));
        OpmlImporter.ImportResult result = importer.importFromReader(new StringReader(opml));
        assertEquals(1, result.imported.size());
        assertEquals(1, result.failed.size());
        assertEquals(1, database.getAllFeeds().size());
        assertEquals("Sample Podcast", database.getAllFeeds().get(0).getTitle());
    }

    @Test
    public void testHtmlExport() throws Exception {
        new FeedUpdater(database).subscribe(baseUrl + "/feed.xml");
        StringWriter writer = new StringWriter();
        new OpmlImporter(database, new FeedUpdater(database)).exportHtmlToWriter(writer);
        assertTrue(writer.toString().contains("Sample Podcast"));
    }
}
