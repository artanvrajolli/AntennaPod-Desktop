package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.parser.transcript.TranscriptParser;
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

public class DesktopTranscriptTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        byte[] srt = Files.readAllBytes(
                new File(getClass().getClassLoader().getResource("sample-transcript.srt").toURI()).toPath());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transcript.srt", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/srt");
            exchange.sendResponseHeaders(200, srt.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(srt);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private static String resource(String name) throws Exception {
        return Files.readString(new File(
                DesktopTranscriptTest.class.getClassLoader().getResource(name).toURI()).toPath(),
                StandardCharsets.UTF_8);
    }

    @Test
    public void testParseSrt() throws Exception {
        Transcript transcript = TranscriptParser.parse(resource("sample-transcript.srt"), "application/srt");
        assertNotNull(transcript);
        assertTrue(transcript.getSegmentCount() >= 2);
        assertTrue(transcript.getSegmentAt(0).getWords().contains("Hello"));
    }

    @Test
    public void testParseVtt() throws Exception {
        Transcript transcript = TranscriptParser.parse(resource("sample-transcript.vtt"), "text/vtt");
        assertNotNull(transcript);
        assertTrue(transcript.getSegmentCount() >= 2);
    }

    @Test
    public void testParseJson() throws Exception {
        Transcript transcript = TranscriptParser.parse(resource("sample-transcript.json"), "application/json");
        assertNotNull(transcript);
        assertEquals(2, transcript.getSegmentCount());
        assertEquals("Jane", transcript.getSegmentAt(0).getSpeaker());
    }

    @Test
    public void testFetchAndCache() throws Exception {
        FeedItem item = new FeedItem();
        item.setId(12345);
        item.setTranscriptUrl("application/srt", baseUrl + "/transcript.srt");
        Transcript first = TranscriptFetcher.fetch(item);
        assertTrue(first.getSegmentCount() >= 2);
        server.stop(0);
        Transcript second = TranscriptFetcher.fetch(item);
        assertEquals(first.getSegmentCount(), second.getSegmentCount());
    }

    @Test
    public void testUnparseableResponseIsNotCached() throws Exception {
        String[] body = {"<html>Service temporarily unavailable</html>"};
        server.createContext("/flaky.json", exchange -> {
            byte[] bytes = body[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        FeedItem item = new FeedItem();
        item.setId(777);
        item.setTranscriptUrl("application/json", baseUrl + "/flaky.json");
        try {
            TranscriptFetcher.fetch(item);
            org.junit.Assert.fail("an HTML error page is not a transcript");
        } catch (java.io.IOException expected) {
            // fine
        }
        assertTrue(!TranscriptFetcher.cacheFile(777, baseUrl + "/flaky.json").exists());

        body[0] = resource("sample-transcript.json");
        assertEquals(2, TranscriptFetcher.fetch(item).getSegmentCount());
    }

    @Test
    public void testMovedTranscriptIsFetchedAgain() throws Exception {
        FeedItem item = new FeedItem();
        item.setId(4242);
        item.setTranscriptUrl("application/srt", baseUrl + "/transcript.srt");
        TranscriptFetcher.fetch(item);

        // the same episode after a refresh that moved its transcript
        FeedItem refreshed = new FeedItem();
        refreshed.setId(4242);
        refreshed.setTranscriptUrl("application/srt", baseUrl + "/moved.srt");
        try {
            TranscriptFetcher.fetch(refreshed);
            org.junit.Assert.fail("the old address's copy must not answer for the new one");
        } catch (java.io.IOException expected) {
            // /moved.srt does not exist on the test server
        }
    }
}
