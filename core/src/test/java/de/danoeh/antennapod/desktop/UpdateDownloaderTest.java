package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The installer download. The guard that matters is the length check: a short or truncated
 * download must never end up somewhere the app would then run it.
 */
public class UpdateDownloaderTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private static final int PAYLOAD = 64 * 1024;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        // the shared HTTP client keeps its cache under the data directory
        DesktopHttp.init();
        byte[] payload = new byte[PAYLOAD];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/setup.exe", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/missing.exe", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private UpdateChecker.Release release(String path, long declaredSize) {
        return new UpdateChecker.Release("9.9.9", "notes", baseUrl + "/page",
                baseUrl + path, "setup.exe", declaredSize);
    }

    @Test
    public void testDownloadsTheInstallerAndReportsProgress() throws Exception {
        AtomicLong lastRead = new AtomicLong();
        File file = UpdateDownloader.download(release("/setup.exe", PAYLOAD),
                (read, total) -> lastRead.set(read));
        assertTrue(file.isFile());
        assertEquals(PAYLOAD, file.length());
        assertEquals(PAYLOAD, lastRead.get());
        assertEquals("setup.exe", file.getName());
    }

    @Test
    public void testAShortDownloadIsRejectedAndNotKept() {
        // the release claims more bytes than the server will send
        try {
            UpdateDownloader.download(release("/setup.exe", PAYLOAD * 2L), null);
            fail("a download shorter than the release said must not be accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("expected"));
        }
        File target = new File(UpdateDownloader.updateDir(), "setup.exe");
        assertFalse("the short download must not be left behind", target.exists());
        assertFalse(new File(target.getAbsolutePath() + ".part").exists());
    }

    @Test
    public void testAFailedRequestLeavesNothingBehind() {
        try {
            UpdateDownloader.download(release("/missing.exe", PAYLOAD), null);
            fail("a 404 must not produce a file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("404"));
        }
        assertFalse(new File(UpdateDownloader.updateDir(), "setup.exe").exists());
    }

    @Test
    public void testAReleaseWithNoInstallerIsRefused() {
        UpdateChecker.Release noInstaller =
                new UpdateChecker.Release("9.9.9", "n", baseUrl, null, null, 0);
        try {
            UpdateDownloader.download(noInstaller, null);
            fail("there is nothing to download");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no installer"));
        }
    }

    @Test
    public void testClearRemovesDownloadedInstallers() throws Exception {
        UpdateDownloader.download(release("/setup.exe", PAYLOAD), null);
        assertTrue(new File(UpdateDownloader.updateDir(), "setup.exe").exists());
        UpdateDownloader.clear();
        assertFalse(new File(UpdateDownloader.updateDir(), "setup.exe").exists());
    }

    @Test
    public void testARedownloadReplacesTheOlderFile() throws Exception {
        File dir = UpdateDownloader.updateDir();
        dir.mkdirs();
        Files.write(new File(dir, "setup.exe").toPath(), new byte[]{1, 2, 3});
        File file = UpdateDownloader.download(release("/setup.exe", PAYLOAD), null);
        assertEquals(PAYLOAD, file.length());
    }
}
