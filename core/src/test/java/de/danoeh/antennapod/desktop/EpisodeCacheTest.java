package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EpisodeCacheTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private DesktopDatabase database;
    private EpisodeCache cache;
    private long feedId;
    private int bodyBytes = 4096;
    private boolean enabled;
    private boolean removeAfterFinish;
    private int limitMb;
    private int prefetchCount;

    @Before
    public void setUp() throws Exception {
        System.setProperty("antennapod.desktop.dataDir", tempFolder.getRoot().getAbsolutePath());
        enabled = DesktopPreferences.getEpisodeCacheEnabled();
        removeAfterFinish = DesktopPreferences.getEpisodeCacheRemoveAfterFinish();
        limitMb = DesktopPreferences.getEpisodeCacheLimitMb();
        prefetchCount = DesktopPreferences.getEpisodeCachePrefetchCount();
        DesktopPreferences.setEpisodeCacheEnabled(true);
        DesktopPreferences.setEpisodeCacheRemoveAfterFinish(true);
        DesktopPreferences.setEpisodeCacheLimitMb(2048);
        DesktopPreferences.setEpisodeCachePrefetchCount(2);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ep.mp3", exchange -> {
            byte[] bytes = new byte[bodyBytes];
            Arrays.fill(bytes, (byte) 'x');
            exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/broken.mp3", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        Feed feed = new Feed("http://example.com/feed.xml", null, "Cache Feed");
        database.insertFeed(feed);
        feedId = feed.getId();
        cache = new EpisodeCache(database);
    }

    @After
    public void tearDown() throws Exception {
        cache.shutdown();
        server.stop(0);
        database.close();
        DesktopPreferences.setEpisodeCacheEnabled(enabled);
        DesktopPreferences.setEpisodeCacheRemoveAfterFinish(removeAfterFinish);
        DesktopPreferences.setEpisodeCacheLimitMb(limitMb);
        DesktopPreferences.setEpisodeCachePrefetchCount(prefetchCount);
        System.clearProperty("antennapod.desktop.dataDir");
    }

    private FeedMedia episode(String title, String url) throws Exception {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        long itemId = database.insertItem(feedId, item);
        FeedMedia media = new FeedMedia(item, url, 0, "audio/mpeg");
        database.insertMedia(itemId, media);
        return media;
    }

    private FeedMedia cachedEpisode(String title) throws Exception {
        FeedMedia media = episode(title, baseUrl + "/ep.mp3");
        return cacheNow(media);
    }

    private FeedMedia cacheNow(FeedMedia media) throws Exception {
        cache.cache(media);
        for (int attempt = 0; attempt < 200 && cache.isCaching(media.getId()); attempt++) {
            Thread.sleep(50);
        }
        assertTrue("episode was not cached in time", media.cacheFileAvailable());
        return media;
    }

    private FeedMedia pinDownload(FeedMedia media, String name) throws Exception {
        File download = new File(new File(DesktopPreferences.getMediaDir(), String.valueOf(feedId)), name);
        download.getParentFile().mkdirs();
        Files.writeString(download.toPath(), "downloaded");
        media.setLocalFileUrl(download.getAbsolutePath());
        media.setDownloaded(true, System.currentTimeMillis());
        database.updateMedia(media);
        return media;
    }

    private static void touch(FeedMedia media, long ageMs) {
        assertTrue(new File(media.getCacheFileUrl())
                .setLastModified(System.currentTimeMillis() - ageMs));
    }

    @Test
    public void testCacheStoresFileAndSurvivesReload() throws Exception {
        FeedMedia media = cachedEpisode("Episode One");

        assertNotNull(media.getCacheFileUrl());
        File file = new File(media.getCacheFileUrl());
        assertTrue(file.exists());
        assertTrue(file.getAbsolutePath()
                .startsWith(DesktopPreferences.getEpisodeCacheDir().getAbsolutePath()));
        assertEquals(bodyBytes, file.length());
        assertFalse("a cached episode is not a download", media.isDownloaded());
        assertNull(media.getLocalFileUrl());

        FeedMedia reloaded = database.getMedia(media.getId());
        assertNotNull(reloaded.getCacheFileUrl());
        assertEquals(media.getCacheFileUrl(), reloaded.getCacheFileUrl());
        assertTrue(reloaded.cacheFileAvailable());
        assertEquals(media.getCacheFileUrl(), reloaded.playableFileUrl());
        assertEquals(1, cache.count());
        assertEquals(bodyBytes, cache.sizeBytes());
    }

    @Test
    public void testDownloadedEpisodeIsNeitherCachedNorReplaced() throws Exception {
        FeedMedia media = pinDownload(episode("Downloaded", baseUrl + "/ep.mp3"), "dl.mp3");

        cache.cache(media);
        Thread.sleep(100);

        assertFalse("pinned downloads must not be cached", cache.isCaching(media.getId()));
        assertNull(media.getCacheFileUrl());
        assertEquals(media.getLocalFileUrl(), media.playableFileUrl());
        assertTrue(new File(media.getLocalFileUrl()).exists());
    }

    @Test
    public void testEvictRemovesOnlyTheCachedCopy() throws Exception {
        FeedMedia cached = cachedEpisode("Cached Only");
        String cachePath = cached.getCacheFileUrl();

        assertTrue(cache.evict(cached));
        assertFalse(new File(cachePath).exists());
        assertNull(database.getMedia(cached.getId()).getCacheFileUrl());

        FeedMedia downloaded = pinDownload(episode("Downloaded", baseUrl + "/ep.mp3"), "keep.mp3");
        assertFalse("evicting must ignore pinned downloads", cache.evict(downloaded));
        assertTrue(new File(downloaded.getLocalFileUrl()).exists());
        assertEquals(downloaded.getLocalFileUrl(),
                database.getMedia(downloaded.getId()).getLocalFileUrl());
        assertNull(database.getMedia(downloaded.getId()).getCacheFileUrl());
    }

    @Test
    public void testTrimEvictsLeastRecentlyUsedAndKeepsProtected() throws Exception {
        bodyBytes = 600_000;
        FeedMedia oldest = cachedEpisode("Oldest");
        FeedMedia protectedOne = cachedEpisode("Protected");
        FeedMedia newest = cachedEpisode("Newest");

        touch(oldest, 90_000);
        touch(protectedOne, 60_000);
        touch(newest, 30_000);

        DesktopPreferences.setEpisodeCacheLimitMb(1);
        cache.setProtectedIdsSupplier(() -> Set.of(protectedOne.getId()));

        assertEquals(2, cache.trim());
        assertFalse("the least recently used entry goes first",
                new File(oldest.getCacheFileUrl()).exists());
        assertTrue("protected entries outlive newer ones",
                new File(protectedOne.getCacheFileUrl()).exists());
        assertFalse(new File(newest.getCacheFileUrl()).exists());
        assertNull(database.getMedia(oldest.getId()).getCacheFileUrl());
        assertNotNull(database.getMedia(protectedOne.getId()).getCacheFileUrl());
    }

    @Test
    public void testTrimKeepsEverythingUnderTheLimit() throws Exception {
        FeedMedia first = cachedEpisode("First");
        FeedMedia second = cachedEpisode("Second");

        DesktopPreferences.setEpisodeCacheLimitMb(2048);

        assertEquals(0, cache.trim());
        assertTrue(new File(first.getCacheFileUrl()).exists());
        assertTrue(new File(second.getCacheFileUrl()).exists());
    }

    @Test
    public void testClearKeepsProtectedEntries() throws Exception {
        FeedMedia playing = cachedEpisode("Playing");
        FeedMedia other = cachedEpisode("Other");
        cache.setProtectedIdsSupplier(() -> Set.of(playing.getId()));

        assertEquals(1, cache.clear());
        assertTrue(new File(playing.getCacheFileUrl()).exists());
        assertFalse(new File(other.getCacheFileUrl()).exists());
        assertNull(database.getMedia(other.getId()).getCacheFileUrl());
    }

    @Test
    public void testDisabledCacheDoesNotStartDownloads() throws Exception {
        DesktopPreferences.setEpisodeCacheEnabled(false);
        FeedMedia media = episode("Disabled", baseUrl + "/ep.mp3");

        cache.cache(media);
        Thread.sleep(100);

        assertFalse(cache.isCaching(media.getId()));
        assertNull(media.getCacheFileUrl());
        assertFalse(EpisodeCache.cacheFileFor(media).exists());
    }

    @Test
    public void testFailedDownloadLeavesNothingBehind() throws Exception {
        FeedMedia media = episode("Broken", baseUrl + "/broken.mp3");
        cache.cache(media);
        for (int attempt = 0; attempt < 200 && cache.isCaching(media.getId()); attempt++) {
            Thread.sleep(50);
        }

        File target = EpisodeCache.cacheFileFor(media);
        assertFalse(target.exists());
        assertFalse(new File(target.getAbsolutePath() + ".part").exists());
        assertNull(media.getCacheFileUrl());
        assertNull(database.getMedia(media.getId()).getCacheFileUrl());
    }

    @Test
    public void testAdoptsFileAlreadyOnDisk() throws Exception {
        FeedMedia media = episode("On Disk", baseUrl + "/ep.mp3");
        File target = EpisodeCache.cacheFileFor(media);
        target.getParentFile().mkdirs();
        Files.writeString(target.toPath(), "existing");

        cache.cache(media);

        assertEquals(target.getAbsolutePath(), media.getCacheFileUrl());
        assertEquals(target.getAbsolutePath(), database.getMedia(media.getId()).getCacheFileUrl());
        assertFalse(cache.isCaching(media.getId()));
    }

    @Test
    public void testEvictRetriesWhenTheFileCannotBeDeletedYet() throws Exception {
        FeedMedia media = cachedEpisode("Locked");
        File file = new File(media.getCacheFileUrl());
        // a non-empty directory cannot be deleted, standing in for a file the player still holds
        assertTrue(file.delete());
        assertTrue(file.mkdir());
        assertTrue(new File(file, "held").createNewFile());

        assertFalse("the first attempt cannot delete the file", cache.evict(media));
        assertNotNull("the episode stays queued for another attempt",
                database.getMedia(media.getId()).getCacheFileUrl());

        // the player lets go: the path can be removed now, and the pending eviction finishes the job
        assertTrue(new File(file, "held").delete());
        assertTrue(file.delete());
        cache.retryPendingEvictions();

        assertFalse(file.exists());
        assertNull(database.getMedia(media.getId()).getCacheFileUrl());
        assertEquals(0, cache.count());
    }

    @Test
    public void testPendingEvictionSkipsWhatPlaybackStillNeeds() throws Exception {
        FeedMedia media = cachedEpisode("Locked But Playing");
        File file = new File(media.getCacheFileUrl());
        assertTrue(file.delete());
        assertTrue(file.mkdir());
        assertTrue(new File(file, "held").createNewFile());

        assertFalse(cache.evict(media));
        cache.setProtectedIdsSupplier(() -> Set.of(media.getId()));
        assertTrue(new File(file, "held").delete());
        assertTrue(file.delete());
        cache.retryPendingEvictions();

        cache.setProtectedIdsSupplier(Set::of);
        cache.retryPendingEvictions();
        assertNull(database.getMedia(media.getId()).getCacheFileUrl());
    }

    @Test
    public void testSweepFinishedDropsLeftoversFromTheLastSession() throws Exception {
        FeedMedia finished = cachedEpisode("Finished");
        FeedMedia pending = cachedEpisode("Still To Play");
        String finishedPath = finished.getCacheFileUrl();
        database.setItemState(finished.getItem().getId(), FeedItem.PLAYED);

        assertEquals(1, cache.sweepFinished());
        assertFalse(new File(finishedPath).exists());
        assertNull(database.getMedia(finished.getId()).getCacheFileUrl());
        assertTrue(new File(pending.getCacheFileUrl()).exists());
        assertNotNull(database.getMedia(pending.getId()).getCacheFileUrl());
    }

    @Test
    public void testCacheFileSitsUnderTheFeedFolder() throws Exception {
        FeedMedia media = episode("Nested", baseUrl + "/ep.mp3");
        File target = EpisodeCache.cacheFileFor(media);

        assertEquals(DesktopPreferences.getEpisodeCacheDir(),
                target.getParentFile().getParentFile());
        assertEquals(String.valueOf(feedId), target.getParentFile().getName());
        assertEquals(media.getId() + "-ep.mp3", target.getName());
    }

    @Test
    public void testEpisodesSharingAFileNameDoNotCollide() throws Exception {
        FeedMedia first = cachedEpisode("First");
        bodyBytes = 8192;
        FeedMedia second = cacheNow(episode("Second", baseUrl + "/ep.mp3"));

        assertFalse(first.getCacheFileUrl().equals(second.getCacheFileUrl()));
        assertTrue(new File(first.getCacheFileUrl()).exists());
        assertTrue(new File(second.getCacheFileUrl()).exists());
        assertEquals(2, cache.count());

        assertTrue(cache.evict(first));
        assertTrue("evicting one episode keeps the other cached",
                new File(second.getCacheFileUrl()).exists());
        assertEquals(1, cache.count());
    }
}
