package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Password-protected feeds: HTTP basic auth on subscribe and refresh, logins kept out of URLs. */
public class DesktopFeedAuthTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private DesktopDatabase database;
    private FeedUpdater updater;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        byte[] feedXml = Files.readAllBytes(
                new File(getClass().getClassLoader().getResource("sample-feed.xml").toURI()).toPath());
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "user:s3cr3t:x".getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private.xml", exchange -> {
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"premium\"");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, feedXml.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(feedXml);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        updater = new FeedUpdater(database);
        FeedCredentials.setAll(Collections.emptyMap());
    }

    @After
    public void tearDown() throws Exception {
        FeedCredentials.setAll(Collections.emptyMap());
        server.stop(0);
        database.close();
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testWithoutALoginTheFeedAsksForOne() throws Exception {
        try {
            updater.subscribe(baseUrl + "/private.xml");
            fail("a 401 feed must not subscribe");
        } catch (Exception e) {
            assertTrue(FeedUpdater.isAuthRequired(e));
        }
        assertTrue(database.getAllFeeds().isEmpty());
    }

    @Test
    public void testSubscribeWithALoginStoresItForTheFeed() throws Exception {
        Feed feed = updater.subscribe(baseUrl + "/private.xml",
                new FeedCredentials.Login("user", "s3cr3t:x"));
        assertEquals("Sample Podcast", feed.getTitle());
        assertEquals(baseUrl + "/private.xml", feed.getDownloadUrl());
        FeedCredentials.Login stored = database.getFeedCredentials(feed.getId());
        assertEquals("user", stored.username);
        assertEquals("s3cr3t:x", stored.password);
    }

    @Test
    public void testLoginWrittenIntoTheUrlIsTakenOutOfIt() throws Exception {
        String url = "http://user:s3cr3t%3Ax@127.0.0.1:" + server.getAddress().getPort() + "/private.xml";
        Feed feed = updater.subscribe(url);
        assertEquals("the stored (and synced) URL must not carry the password",
                baseUrl + "/private.xml", feed.getDownloadUrl());
        assertEquals("s3cr3t:x", database.getFeedCredentials(feed.getId()).password);
    }

    @Test
    public void testRefreshAfterARestartUsesTheStoredLogin() throws Exception {
        Feed feed = updater.subscribe(baseUrl + "/private.xml",
                new FeedCredentials.Login("user", "s3cr3t:x"));
        // a new session starts with nothing in memory until the logins are read back
        FeedCredentials.setAll(Collections.emptyMap());
        updater.reloadCredentials();
        updater.refresh(database.getFeed(feed.getId()));
    }

    @Test
    public void testUnsubscribeDropsTheLogin() throws Exception {
        Feed feed = updater.subscribe(baseUrl + "/private.xml",
                new FeedCredentials.Login("user", "s3cr3t:x"));
        updater.unsubscribe(feed.getId());
        assertNull(database.getFeedCredentials(feed.getId()));
        assertTrue(database.getCredentialsByHost().isEmpty());
    }

    @Test
    public void testClearingTheLogin() throws Exception {
        Feed feed = updater.subscribe(baseUrl + "/private.xml",
                new FeedCredentials.Login("user", "s3cr3t:x"));
        updater.setCredentials(feed.getId(), null);
        assertNull(database.getFeedCredentials(feed.getId()));
        assertNull(FeedCredentials.forHost("127.0.0.1"));
    }

    @Test
    public void testUrlHelpers() {
        assertEquals("example.com", FeedCredentials.hostOf("https://EXAMPLE.com/feed"));
        assertNull(FeedCredentials.hostOf("not a url"));
        FeedCredentials.Login login = FeedCredentials.fromUserInfo("https://me%40mail.com:p%40ss@example.com/f");
        assertNotNull(login);
        assertEquals("me@mail.com", login.username);
        assertEquals("p@ss", login.password);
        assertEquals("https://example.com/f",
                FeedCredentials.withoutUserInfo("https://me%40mail.com:p%40ss@example.com/f"));
        assertEquals("https://example.com/f", FeedCredentials.withoutUserInfo("https://example.com/f"));
        assertNull(FeedCredentials.fromUserInfo("https://example.com/f"));
        assertFalse(FeedUpdater.isAuthRequired(new java.io.IOException("other")));
    }
}
