package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.core.util.Pair;
import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.net.sync.service.EpisodeActionFilter;
import de.danoeh.antennapod.net.sync.serviceinterface.EpisodeAction;
import de.danoeh.antennapod.net.sync.serviceinterface.EpisodeActionChanges;
import de.danoeh.antennapod.net.sync.serviceinterface.ISyncService;
import de.danoeh.antennapod.net.sync.serviceinterface.SubscriptionChanges;
import de.danoeh.antennapod.net.sync.serviceinterface.UploadChangesResponse;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopSyncTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private DesktopDatabase database;
    private FakeSyncService fakeService;

    static class FakeSyncService implements ISyncService {
        List<String> remoteAdded = new ArrayList<>();
        List<String> remoteRemoved = new ArrayList<>();
        List<EpisodeAction> remoteActions = new ArrayList<>();
        List<String> uploadedAdded = new ArrayList<>();
        List<String> uploadedRemoved = new ArrayList<>();
        List<EpisodeAction> uploadedActions = new ArrayList<>();

        @Override
        public void login() {
        }

        @Override
        public SubscriptionChanges getSubscriptionChanges(long lastSync) {
            return new SubscriptionChanges(new ArrayList<>(remoteAdded),
                    new ArrayList<>(remoteRemoved), lastSync + 1);
        }

        @Override
        public UploadChangesResponse uploadSubscriptionChanges(List<String> added, List<String> removed) {
            uploadedAdded.addAll(added);
            uploadedRemoved.addAll(removed);
            return new UploadChangesResponse(42) {
            };
        }

        @Override
        public EpisodeActionChanges getEpisodeActionChanges(long lastSync) {
            return new EpisodeActionChanges(new ArrayList<>(remoteActions), lastSync + 1);
        }

        @Override
        public UploadChangesResponse uploadEpisodeActions(List<EpisodeAction> actions) {
            uploadedActions.addAll(actions);
            return new UploadChangesResponse(43) {
            };
        }

        @Override
        public void logout() {
        }
    }

    static class TestSyncManager extends SyncManager {
        final FakeSyncService fake;

        TestSyncManager(DesktopDatabase database, FeedUpdater updater, FakeSyncService fake) {
            super(database, updater);
            this.fake = fake;
        }

        @Override
        protected ISyncService createService() {
            return fake;
        }
    }

    static class FakeGpodnetService extends de.danoeh.antennapod.net.sync.gpoddernet.GpodnetService {
        final List<String> deviceFeeds;

        FakeGpodnetService(List<String> deviceFeeds) {
            super(de.danoeh.antennapod.net.common.AntennapodHttpClient.getHttpClient(),
                    "example.com", "phone-device", "u", "p");
            this.deviceFeeds = deviceFeeds;
        }

        @Override
        public void login() {
        }

        @Override
        public void logout() {
        }

        @Override
        public SubscriptionChanges getSubscriptionChanges(long timestamp) {
            return new SubscriptionChanges(new ArrayList<>(deviceFeeds), new ArrayList<>(), 1);
        }
    }

    static class DeviceImportSyncManager extends SyncManager {
        final FakeGpodnetService fakeGpodnet;

        DeviceImportSyncManager(DesktopDatabase database, FeedUpdater updater, FakeGpodnetService fake) {
            super(database, updater);
            this.fakeGpodnet = fake;
        }

        @Override
        protected de.danoeh.antennapod.net.sync.gpoddernet.GpodnetService createServiceForDevice(String deviceId) {
            return fakeGpodnet;
        }
    }

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        DesktopPreferences.setSyncProvider("gpodder");
        DesktopPreferences.setSyncUsername("testuser");
        byte[] feedXml = Files.readAllBytes(
                new File(getClass().getClassLoader().getResource("sample-feed.xml").toURI()).toPath());
        String feed = new String(feedXml, StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] mutableBase = new String[1];
        server.createContext("/", exchange -> {
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
        fakeService = new FakeSyncService();
    }

    @After
    public void tearDown() throws Exception {
        server.stop(0);
        database.close();
        DesktopPreferences.setSyncProvider("none");
        DesktopPreferences.setSyncUsername("");
        System.clearProperty("antennapod.desktop.dataDir");
    }

    @Test
    public void testTwoWaySubscriptionSync() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        Feed local = updater.subscribe(baseUrl + "/local.xml");
        fakeService.remoteAdded.add(baseUrl + "/remote.xml");

        SyncManager manager = new TestSyncManager(database, updater, fakeService);
        SyncManager.SyncResult result = manager.sync();

        assertEquals(1, result.subscriptionsAdded);
        assertEquals(2, database.getAllFeeds().size());
        assertTrue(fakeService.uploadedAdded.contains(baseUrl + "/local.xml"));
        assertEquals(0, result.actionsUploaded);
    }

    @Test
    public void testEpisodeActionUploadAndDownload() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        Feed feed = updater.subscribe(baseUrl + "/local.xml");
        Feed stored = database.getFeed(feed.getId());
        FeedItem item = stored.getItems().get(0);
        item.setFeed(stored);
        item.getMedia().setPosition(30000);
        item.getMedia().setDuration(3600000);

        SyncManager manager = new TestSyncManager(database, updater, fakeService);
        manager.recordPlayAction(item.getMedia());
        assertEquals(1, database.getQueuedSyncActions().size());

        EpisodeAction remote = new EpisodeAction.Builder(
                feed.getDownloadUrl(), item.getMedia().getDownloadUrl(), EpisodeAction.Action.PLAY)
                .timestamp(new Date(System.currentTimeMillis() + 60000))
                .guid(item.getItemIdentifier())
                .started(0).position(120).total(3600).build();
        fakeService.remoteActions.add(remote);

        SyncManager.SyncResult result = manager.sync();
        assertEquals(1, result.actionsUploaded);
        assertEquals(1, result.actionsApplied);
        assertTrue(database.getQueuedSyncActions().isEmpty());
        assertEquals(120000, database.getMedia(item.getMedia().getId()).getPosition());
    }

    @Test
    public void testImportFromDevice() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        FakeGpodnetService fakeGpodnet = new FakeGpodnetService(
                java.util.Collections.singletonList(baseUrl + "/phone.xml"));
        SyncManager manager = new DeviceImportSyncManager(database, updater, fakeGpodnet);
        int added = manager.importFromDevice("phone-device");
        assertEquals(1, added);
        assertEquals(1, database.getAllFeeds().size());
        assertEquals(0, manager.importFromDevice("phone-device"));
    }

    @Test
    public void testPlayedStateSyncRoundTrip() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        Feed feed = updater.subscribe(baseUrl + "/local.xml");
        Feed stored = database.getFeed(feed.getId());
        FeedItem item = stored.getItems().get(0);
        item.setFeed(stored);
        item.getMedia().setDuration(600000);

        SyncManager manager = new TestSyncManager(database, updater, fakeService);
        item.setPlayed(true);
        manager.recordPlayedState(item, true);
        assertEquals(1, database.getQueuedSyncActions().size());
        assertEquals(600, database.getQueuedSyncActions().get(0).position);

        SyncManager.SyncResult result = manager.sync();
        assertEquals(1, result.actionsUploaded);
        assertEquals(600, fakeService.uploadedActions.get(0).getPosition());

        EpisodeAction remoteUnplayed = new EpisodeAction.Builder(
                feed.getDownloadUrl(), item.getMedia().getDownloadUrl(), EpisodeAction.Action.PLAY)
                .timestamp(new Date(System.currentTimeMillis() + 60000))
                .guid(item.getItemIdentifier())
                .started(0).position(0).total(600).build();
        fakeService.remoteActions.add(remoteUnplayed);
        SyncManager.SyncResult second = manager.sync();
        assertEquals(1, second.actionsApplied);
        FeedItem reloaded = database.getItem(item.getId());
        assertTrue(!reloaded.isPlayed());
        assertEquals(0, reloaded.getMedia().getPosition());
    }

    @Test
    public void testNewerLocalActionSurvivesSync() throws Exception {
        FeedUpdater updater = new FeedUpdater(database);
        Feed feed = updater.subscribe(baseUrl + "/local.xml");
        Feed stored = database.getFeed(feed.getId());
        FeedItem item = stored.getItems().get(0);
        item.setFeed(stored);
        item.getMedia().setPosition(30000);
        item.getMedia().setDuration(3600000);
        database.updateMedia(item.getMedia());

        SyncManager manager = new TestSyncManager(database, updater, fakeService);
        manager.recordPlayAction(item.getMedia());

        EpisodeAction staleRemote = new EpisodeAction.Builder(
                feed.getDownloadUrl(), item.getMedia().getDownloadUrl(), EpisodeAction.Action.PLAY)
                .timestamp(new Date(System.currentTimeMillis() - 60000))
                .guid(item.getItemIdentifier())
                .started(0).position(999).total(3600).build();
        fakeService.remoteActions.add(staleRemote);

        SyncManager.SyncResult result = manager.sync();
        assertEquals(1, result.actionsUploaded);
        assertEquals(0, result.actionsApplied);
        assertEquals(30000, database.getMedia(item.getMedia().getId()).getPosition());
    }

    @Test
    public void testActionFilterPrefersNewerLocalAction() {
        EpisodeAction remote = new EpisodeAction.Builder("podcast", "episode", EpisodeAction.Action.PLAY)
                .timestamp(new Date(1000)).started(0).position(10).total(100).build();
        EpisodeAction local = new EpisodeAction.Builder("podcast", "episode", EpisodeAction.Action.PLAY)
                .timestamp(new Date(2000)).started(0).position(50).total(100).build();
        List<EpisodeAction> queued = new ArrayList<>();
        queued.add(local);
        Map<Pair<String, String>, EpisodeAction> overriding =
                EpisodeActionFilter.getRemoteActionsOverridingLocalActions(
                        java.util.Collections.singletonList(remote), queued);
        assertTrue(overriding.isEmpty());
    }

    @Test
    public void testEpisodeActionJsonRoundTrip() throws Exception {
        EpisodeAction action = new EpisodeAction.Builder("podcast", "episode", EpisodeAction.Action.PLAY)
                .timestamp(new Date(1700000000000L)).guid("guid-1").started(5).position(60).total(600).build();
        JSONObject json = action.writeToJsonObject();
        EpisodeAction parsed = EpisodeAction.readFromJsonObject(json);
        assertEquals("podcast", parsed.getPodcast());
        assertEquals("guid-1", parsed.getGuid());
        assertEquals(60, parsed.getPosition());
        assertEquals(600, parsed.getTotal());
    }
}
