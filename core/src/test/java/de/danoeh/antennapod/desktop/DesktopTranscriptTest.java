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
}
