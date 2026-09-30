package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.PodcastPerson;
import de.danoeh.antennapod.model.feed.Soundbite;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Podcasting 2.0 funding, people and soundbites: parsed, stored, and kept current by refresh. */
public class DesktopPodcast20Test {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private DesktopDatabase database;
    private volatile String guest = "Grace Hopper";

    private String feed() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">\n"
                + "<channel><title>Namespace Show</title><link>https://example.com</link>\n"
                + "<description>d</description>\n"
                + "<podcast:funding url=\"https://example.com/support\">Support the show</podcast:funding>\n"
                + "<podcast:person href=\"https://example.com/ada\">Ada Lovelace</podcast:person>\n"
                + "<item><title>Episode</title><guid>e1</guid>\n"
                + "<enclosure url=\"https://example.com/e1.mp3\" length=\"1\" type=\"audio/mpeg\"/>\n"
                + "<podcast:person role=\"Guest\" img=\"https://example.com/g.jpg\">" + guest + "</podcast:person>\n"
                + "<podcast:soundbite startTime=\"73.5\" duration=\"60\">The best minute</podcast:soundbite>\n"
                + "<podcast:soundbite startTime=\"1200\" duration=\"30\" />\n"
                + "<podcast:soundbite startTime=\"oops\" duration=\"30\">broken</podcast:soundbite>\n"
                + "</item></channel></rss>";
    }

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.xml", exchange -> {
            byte[] bytes = feed().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
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
    public void testExtrasAreStoredAndRefreshed() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        Feed subscribed = updater.subscribe(baseUrl + "/feed.xml");

        Feed stored = database.getFeed(subscribed.getId());
        assertEquals("https://example.com/support", stored.getPaymentLinks().get(0).url);
        assertEquals("Support the show", stored.getPaymentLinks().get(0).content);
        PodcastPerson host = stored.getPersons().get(0);
        assertEquals("Ada Lovelace", host.name);
        assertEquals("host", host.role);
        assertEquals("https://example.com/ada", host.href);

        FeedItem episode = stored.getItems().get(0);
        assertEquals("Grace Hopper", episode.getPersons().get(0).name);
        assertEquals("guest", episode.getPersons().get(0).role);
        assertEquals("https://example.com/g.jpg", episode.getPersons().get(0).img);
        assertNull(episode.getPersons().get(0).href);
        List<Soundbite> soundbites = episode.getSoundbites();
        assertEquals("the broken one is skipped", 2, soundbites.size());
        assertEquals(73_500, soundbites.get(0).startMs);
        assertEquals(60_000, soundbites.get(0).durationMs);
        assertEquals("The best minute", soundbites.get(0).title);
        assertEquals("", soundbites.get(1).title);

        guest = "Margaret Hamilton";
        updater.refresh(stored);
        assertEquals("Margaret Hamilton",
                database.getFeed(subscribed.getId()).getItems().get(0).getPersons().get(0).name);
    }

    @Test
    public void testFeedsWithoutExtrasReadAsNone() throws Exception {
        Feed plain = new Feed("http://example.com/plain.xml", null, "Plain");
        database.insertFeed(plain);
        Feed stored = database.getFeed(plain.getId());
        assertNull(stored.getPaymentLinks());
        assertNull(stored.getPersons());
        assertNull(PodcastExtras.personsFromJson("not json"));
    }
}
