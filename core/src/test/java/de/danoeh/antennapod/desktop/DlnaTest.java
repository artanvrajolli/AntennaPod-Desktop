package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Casting to UPnP/DLNA renderers: device descriptions, SOAP control and the media server. */
public class DlnaTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer fake;
    private final List<String> actions = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    @After
    public void tearDown() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    private static final String DESCRIPTION = "<?xml version=\"1.0\"?>"
            + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>"
            + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
            + "<friendlyName>Living Room TV</friendlyName><serviceList>"
            + "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>"
            + "<controlURL>/upnp/control/rc</controlURL></service>"
            + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
            + "<controlURL>upnp/control/avt</controlURL></service>"
            + "</serviceList></device></root>";

    @Test
    public void testDescriptionGivesNameAndResolvedControlUrls() {
        DlnaRenderer renderer = DlnaRenderer.fromDescription("http://192.168.1.20:52235/dmr/desc.xml", DESCRIPTION);
        assertNotNull(renderer);
        assertEquals("Living Room TV", renderer.name);
        assertEquals("http://192.168.1.20:52235/dmr/upnp/control/avt", renderer.avTransportUrl);
        assertEquals("http://192.168.1.20:52235/upnp/control/rc", renderer.renderingControlUrl);
        assertEquals("192.168.1.20", renderer.host());
    }

    @Test
    public void testNonRenderersAndHostileXmlAreIgnored() {
        assertNull(DlnaRenderer.fromDescription("http://h/d.xml",
                "<root><device><friendlyName>Router</friendlyName></device></root>"));
        assertNull(DlnaRenderer.fromDescription("http://h/d.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]><root>&x;</root>"));
        assertNull(DlnaRenderer.fromDescription("http://h/d.xml", "not xml"));
    }

    @Test
    public void testSsdpAnswersNameOnlyRenderers() {
        String renderer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n"
                + "LOCATION: http://192.168.1.20:52235/dmr/desc.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
        assertEquals("http://192.168.1.20:52235/dmr/desc.xml", DlnaDiscovery.locationOf(renderer));
        assertNull(DlnaDiscovery.locationOf(renderer.replace("MediaRenderer", "InternetGatewayDevice")));
        assertNull(DlnaDiscovery.locationOf("HTTP/1.1 200 OK\r\nST: " + DlnaDiscovery.SEARCH_TARGET + "\r\n\r\n"));
    }

    @Test
    public void testClockBothWays() {
        assertEquals("1:02:03", DlnaRenderer.clock(3_723_000));
        assertEquals("0:00:00", DlnaRenderer.clock(-5));
        assertEquals(3_723_500, DlnaRenderer.parseClock("01:02:03.500"));
        assertEquals(-1, DlnaRenderer.parseClock("NOT_IMPLEMENTED"));
        assertEquals(-1, DlnaRenderer.parseClock(null));
    }

    private DlnaRenderer fakeRenderer(String positionReply, int status) throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            String action = exchange.getRequestHeaders().getFirst("SOAPACTION");
            actions.add(action);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = (action != null && action.contains("GetPositionInfo") ? positionReply
                    : status == 500 ? "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode>"
                            + "<errorDescription>Transition not available</errorDescription></UPnPError>"
                            + "</detail></s:Fault></s:Body></s:Envelope>"
                    : "<s:Envelope><s:Body><u:OkResponse/></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        fake.start();
        String base = "http://127.0.0.1:" + fake.getAddress().getPort();
        return new DlnaRenderer("Fake", base + "/desc.xml", base + "/avt", base + "/rc");
    }

    @Test
    public void testSoapActionsReachTheRenderer() throws Exception {
        DlnaRenderer renderer = fakeRenderer("<s:Envelope><s:Body><u:GetPositionInfoResponse>"
                + "<RelTime>0:01:13</RelTime><TrackDuration>1:00:00</TrackDuration>"
                + "</u:GetPositionInfoResponse></s:Body></s:Envelope>", 200);
        renderer.load("http://192.168.1.5:5000/media/abc.mp3", "Rock & Roll <live>", "audio/mpeg");
        renderer.play();
        renderer.seek(73_000);
        renderer.setVolume(140);
        assertArrayEquals(new int[]{73_000, 3_600_000}, renderer.position());
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", actions.get(0));
        assertTrue(bodies.get(0).contains("<CurrentURI>http://192.168.1.5:5000/media/abc.mp3</CurrentURI>"));
        assertTrue("the title is escaped twice: once in DIDL, once in SOAP",
                bodies.get(0).contains("Rock &amp;amp; Roll &amp;lt;live&amp;gt;"));
        assertTrue(bodies.get(2).contains("<Target>0:01:13</Target>"));
        assertTrue(actions.get(3).contains("RenderingControl:1#SetVolume"));
        assertTrue("volume is clamped", bodies.get(3).contains("<DesiredVolume>100</DesiredVolume>"));
    }

    @Test
    public void testRendererErrorsCarryTheirDescription() throws Exception {
        DlnaRenderer renderer = fakeRenderer("", 500);
        try {
            renderer.pause();
            fail("an error reply must throw");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Transition not available"));
        }
    }

    // ------------------------------------------------------------------ media server

    private static HttpURLConnection open(String url, String range) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (range != null) {
            connection.setRequestProperty("Range", range);
        }
        return connection;
    }

    private static byte[] read(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    public void testServesPublishedFilesWithRanges() throws Exception {
        File file = tempFolder.newFile("episode.mp3");
        byte[] content = new byte[1000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(file.toPath(), content);
        try (CastServer server = new CastServer()) {
            String url = server.publish(file, null, "audio/mpeg", "127.0.0.1");
            assertTrue(url, url.startsWith("http://127.0.0.1:" + server.port() + "/media/"));
            assertTrue(url.endsWith(".mp3"));

            HttpURLConnection whole = open(url, null);
            assertEquals(200, whole.getResponseCode());
            assertEquals("Streaming", whole.getHeaderField("transferMode.dlna.org"));
            assertArrayEquals(content, read(whole));

            HttpURLConnection part = open(url, "bytes=100-199");
            assertEquals(206, part.getResponseCode());
            assertEquals("bytes 100-199/1000", part.getHeaderField("Content-Range"));
            byte[] slice = read(part);
            assertEquals(100, slice.length);
            assertEquals((byte) 100, slice[0]);

            assertEquals(416, open(url, "bytes=5000-").getResponseCode());
            assertEquals("nothing else is reachable", 404,
                    open(url.replaceAll("/media/[0-9a-f]+", "/media/0000"), null).getResponseCode());

            String next = server.publish(file, null, "audio/mpeg", "127.0.0.1");
            assertEquals("only the latest publication stays up", 404, open(url, null).getResponseCode());
            assertEquals(200, open(next, null).getResponseCode());
        }
    }

    @Test
    public void testRelaysRemoteStreamsWithTheirRange() throws Exception {
        byte[] remote = "0123456789".getBytes(StandardCharsets.US_ASCII);
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/ep.mp3", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            byte[] body = range != null ? "2345".getBytes(StandardCharsets.US_ASCII) : remote;
            if (range != null) {
                exchange.getResponseHeaders().add("Content-Range", "bytes 2-5/10");
            }
            exchange.sendResponseHeaders(range != null ? 206 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fake.start();
        String remoteUrl = "http://127.0.0.1:" + fake.getAddress().getPort() + "/ep.mp3";
        try (CastServer server = new CastServer()) {
            String url = server.publish(null, remoteUrl, "audio/mpeg", "127.0.0.1");
            assertArrayEquals(remote, read(open(url, null)));
            HttpURLConnection part = open(url, "bytes=2-5");
            assertEquals(206, part.getResponseCode());
            assertEquals("bytes 2-5/10", part.getHeaderField("Content-Range"));
            assertEquals("2345", new String(read(part), StandardCharsets.US_ASCII));
        }
    }

    @Test
    public void testCastSessionHandsTheRendererAFileItCanFetch() throws Exception {
        File file = tempFolder.newFile("local.mp3");
        Files.write(file.toPath(), "audio-bytes".getBytes(StandardCharsets.US_ASCII));
        byte[][] fetched = new byte[1][];
        java.util.concurrent.CountDownLatch gotFile = new java.util.concurrent.CountDownLatch(1);
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            String action = exchange.getRequestHeaders().getFirst("SOAPACTION");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            actions.add(action);
            String reply = "<s:Envelope><s:Body/></s:Envelope>";
            if (action.contains("SetAVTransportURI")) {
                // a renderer fetches what it is given, as the TV would
                String uri = DlnaRenderer.element(body, "CurrentURI");
                new Thread(() -> {
                    try {
                        fetched[0] = read(open(uri, null));
                    } catch (IOException e) {
                        fetched[0] = new byte[0];
                    }
                    gotFile.countDown();
                }).start();
            } else if (action.contains("GetTransportInfo")) {
                reply = "<CurrentTransportState>PLAYING</CurrentTransportState>";
            } else if (action.contains("GetPositionInfo")) {
                reply = "<RelTime>0:00:05</RelTime><TrackDuration>0:01:00</TrackDuration>";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fake.start();
        String base = "http://127.0.0.1:" + fake.getAddress().getPort();
        DlnaRenderer renderer = new DlnaRenderer("Fake", base + "/desc.xml", base + "/avt", null);
        de.danoeh.antennapod.model.feed.FeedItem item = new de.danoeh.antennapod.model.feed.FeedItem();
        item.setTitle("On the TV");
        de.danoeh.antennapod.model.feed.FeedMedia media = new de.danoeh.antennapod.model.feed.FeedMedia(
                item, "https://example.com/remote.mp3", 11, "audio/mpeg");
        media.setLocalFileUrl(file.getAbsolutePath());
        media.setDownloaded(true, 1);
        java.util.concurrent.CountDownLatch progressed = new java.util.concurrent.CountDownLatch(1);
        int[] seen = new int[2];
        try (CastServer server = new CastServer();
             CastSession session = new CastSession(renderer, server, new CastSession.Listener() {
                 @Override
                 public void onProgress(int positionMs, int durationMs, boolean playing) {
                     seen[0] = positionMs;
                     seen[1] = durationMs;
                     progressed.countDown();
                 }

                 @Override
                 public void onFinished() {
                 }

                 @Override
                 public void onError(String message) {
                 }
             })) {
            session.cast(media, 0);
            assertTrue(gotFile.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("the downloaded file, not the remote URL", "audio-bytes",
                    new String(fetched[0], StandardCharsets.US_ASCII));
            assertTrue(progressed.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertArrayEquals(new int[]{5_000, 60_000}, seen);
            assertEquals(5_000, session.lastPositionMs());
        }
        assertTrue(actions.get(0).contains("SetAVTransportURI"));
        assertTrue(actions.get(1).contains("#Play"));
    }

    @Test
    public void testFinishedOnlyWhenStoppedAtTheEnd() {
        assertTrue(CastSession.isFinished("STOPPED", 3_598_000, 3_600_000));
        assertTrue(CastSession.isFinished("NO_MEDIA_PRESENT", 3_600_000, 3_600_000));
        org.junit.Assert.assertFalse("stopped halfway on the TV's remote",
                CastSession.isFinished("STOPPED", 1_000_000, 3_600_000));
        org.junit.Assert.assertFalse(CastSession.isFinished("PLAYING", 3_600_000, 3_600_000));
        org.junit.Assert.assertFalse("length unknown", CastSession.isFinished("STOPPED", 5_000, 0));
    }

    @Test
    public void testRangeArithmetic() {
        assertArrayEquals(new long[]{0, 999}, CastServer.span(null, 1000));
        assertArrayEquals(new long[]{900, 999}, CastServer.span("bytes=-100", 1000));
        assertArrayEquals(new long[]{500, 999}, CastServer.span("bytes=500-", 1000));
        assertArrayEquals(new long[]{10, 999}, CastServer.span("bytes=10-5000", 1000));
        assertNull(CastServer.span("bytes=1000-", 1000));
        assertEquals(".m4a", CastServer.extensionFor("audio/mp4"));
    }
}
