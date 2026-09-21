package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps a bounded, self-managing copy of episodes that are playing (or queued to play next) so
 * playback does not have to hit the network again. Unlike a download this copy is disposable: it is
 * dropped as soon as the episode finishes and evicted least-recently-used once the cache is over its
 * size limit. Pinned downloads live in the media directory and are never touched from here.
 */
public final class EpisodeCache {
    private final DesktopDatabase database;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "episode-cache");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();
    /** Episodes whose cached copy could not be deleted yet, usually because the player still holds it. */
    private final Set<Long> pendingEvictions = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "episode-cache-retry");
                thread.setDaemon(true);
                return thread;
            });
    private final java.util.concurrent.atomic.AtomicBoolean retryScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Supplier<Set<Long>> protectedIdsSupplier = Set::of;
    private volatile Consumer<String> statusReporter;

    private static final long RETRY_DELAY_MS = 10_000;

    public EpisodeCache(DesktopDatabase database) {
        this.database = database;
        DesktopHttp.init();
    }

    /** Ids that playback still needs — never evicted, however old they look. */
    public void setProtectedIdsSupplier(Supplier<Set<Long>> supplier) {
        this.protectedIdsSupplier = supplier != null ? supplier : Set::of;
    }

    public void setStatusReporter(Consumer<String> reporter) {
        this.statusReporter = reporter;
    }

    public static File cacheFileFor(FeedMedia media) {
        long feedId = media.getItem() != null ? media.getItem().getFeedId() : 0;
        // the media id keeps episodes whose URLs share a file name from overwriting each other
        String name = media.getId() + "-" + EpisodeDownloader.fileNameFor(media);
        return new File(new File(DesktopPreferences.getEpisodeCacheDir(), String.valueOf(feedId)), name);
    }

    /** Starts caching the episode unless it is already downloaded, cached, or being fetched. */
    public synchronized void cache(FeedMedia media) {
        if (!DesktopPreferences.getEpisodeCacheEnabled()) {
            return;
        }
        if (media == null || media.getId() <= 0 || media.getDownloadUrl() == null) {
            return;
        }
        if (media.localFileAvailable()) {
            return;
        }
        File target = cacheFileFor(media);
        if (target.exists()) {
            // a finished copy is already on disk: adopt it instead of downloading it again
            if (!media.cacheFileAvailable()) {
                media.setCacheFileUrl(target.getAbsolutePath());
                try {
                    database.updateMedia(media);
                } catch (SQLException e) {
                    report("Could not update episode cache: " + e.getMessage());
                }
            }
            return;
        }
        if (isCaching(media.getId())) {
            return;
        }
        running.put(media.getId(), executor.submit(() -> fetch(media, target)));
    }

    public synchronized boolean isCaching(long mediaId) {
        Future<?> future = running.get(mediaId);
        return future != null && !future.isDone();
    }

    public synchronized void cancel(long mediaId) {
        Future<?> future = running.get(mediaId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void fetch(FeedMedia media, File target) {
        try {
            EpisodeDownloader.fetchToFile(media, target, new EpisodeDownloader.ProgressListener() {
                @Override
                public void onProgress(long mediaId, long bytesRead, long totalBytes) {
                }

                @Override
                public void onFinished(long mediaId, File file) {
                }

                @Override
                public void onError(long mediaId, Exception e) {
                }
            });
            media.setCacheFileUrl(target.getAbsolutePath());
            database.updateMedia(media);
        } catch (Exception e) {
            target.delete();
            new File(target.getAbsolutePath() + ".part").delete();
            if (!(e instanceof IOException) || !"Download cancelled".equals(e.getMessage())) {
                report("Could not cache \"" + media.getHumanReadableIdentifier() + "\": " + e.getMessage());
            }
        } finally {
            running.remove(media.getId());
            trim();
        }
    }

    /** Drops the cached copy of a finished episode. Pinned downloads are left alone. */
    public synchronized boolean evict(FeedMedia media) {
        if (media == null || media.getId() <= 0) {
            return false;
        }
        String path = media.getCacheFileUrl();
        if (path == null) {
            // the caller may hold an instance loaded before the episode was cached
            try {
                FeedMedia stored = database.getMedia(media.getId());
                path = stored != null ? stored.getCacheFileUrl() : null;
            } catch (SQLException e) {
                return false;
            }
        }
        if (path == null) {
            return false;
        }
        cancel(media.getId());
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            // the player still holds the file: retry later instead of leaving it in the cache
            pendingEvictions.add(media.getId());
            scheduleRetry();
            return false;
        }
        pendingEvictions.remove(media.getId());
        media.setCacheFileUrl(null);
        try {
            database.updateMedia(media);
        } catch (SQLException e) {
            report("Could not update episode cache: " + e.getMessage());
        }
        return true;
    }

    /**
     * Evicts least recently used entries until the cache fits its configured limit, and retries any
     * eviction that could not delete its file earlier. The protected ids are read before the cache
     * lock is taken: playback holds its own lock while calling into the cache, so asking it for the
     * playing episode under our lock could deadlock.
     */
    public int trim() {
        retryPendingEvictions();
        Set<Long> protectedIds = currentProtectedIds();
        synchronized (this) {
            return trimLocked(protectedIds);
        }
    }

    private int trimLocked(Set<Long> protectedIds) {
        int limitMb = DesktopPreferences.getEpisodeCacheLimitMb();
        if (limitMb <= 0) {
            return 0;
        }
        List<FeedMedia> cached = cachedMedia();
        long total = 0;
        for (FeedMedia media : cached) {
            total += fileLength(media.getCacheFileUrl());
        }
        long limit = limitMb * 1024L * 1024L;
        if (total <= limit) {
            return 0;
        }
        List<FeedMedia> candidates = new ArrayList<>();
        for (FeedMedia media : cached) {
            if (!isProtected(media.getId(), protectedIds)) {
                candidates.add(media);
            }
        }
        candidates.sort(Comparator.comparingLong(EpisodeCache::lastUsed));
        int evicted = 0;
        for (FeedMedia media : candidates) {
            if (total <= limit) {
                break;
            }
            long length = fileLength(media.getCacheFileUrl());
            if (evict(media)) {
                total -= length;
                evicted++;
            }
        }
        if (evicted > 0) {
            report("Episode cache trimmed: removed " + evicted
                    + (evicted == 1 ? " episode" : " episodes"));
        }
        return evicted;
    }

    /** Deletes every cached copy except the ones playback still needs. Returns the number removed. */
    public int clear() {
        Set<Long> protectedIds = currentProtectedIds();
        synchronized (this) {
            int removed = 0;
            for (FeedMedia media : cachedMedia()) {
                if (!isProtected(media.getId(), protectedIds) && evict(media)) {
                    removed++;
                }
            }
            return removed;
        }
    }

    /**
     * Drops the copies of episodes that already finished. Run this at startup: the player can hold a
     * file open until the app exits, so the eviction that should have happened at the end of the last
     * episode may have had to wait until now.
     */
    public int sweepFinished() {
        int removed = 0;
        try {
            for (FeedMedia media : database.getCachedFinishedMedia()) {
                if (evict(media)) {
                    removed++;
                }
            }
        } catch (SQLException e) {
            report("Could not clean up the episode cache: " + e.getMessage());
        }
        return removed;
    }

    public long sizeBytes() {
        return cacheSize(DesktopPreferences.getEpisodeCacheDir());
    }

    public int count() {
        return countFiles(DesktopPreferences.getEpisodeCacheDir());
    }

    public void shutdown() {
        retryScheduler.shutdownNow();
        executor.shutdownNow();
    }

    private void scheduleRetry() {
        if (retryScheduled.compareAndSet(false, true)) {
            try {
                retryScheduler.schedule(this::retryPendingEvictions, RETRY_DELAY_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                retryScheduled.set(false);
            }
        }
    }

    /**
     * Retries evictions that could not delete their file yet. Keeps rescheduling itself while
     * something is left, so a file the player held on to is dropped as soon as it is released.
     */
    void retryPendingEvictions() {
        retryScheduled.set(false);
        if (pendingEvictions.isEmpty()) {
            return;
        }
        Set<Long> protectedIds = currentProtectedIds();
        for (Long mediaId : new ArrayList<>(pendingEvictions)) {
            if (isProtected(mediaId, protectedIds)) {
                continue;
            }
            FeedMedia media = loadMedia(mediaId);
            if (media == null) {
                pendingEvictions.remove(mediaId);
                continue;
            }
            evict(media);
        }
        if (!pendingEvictions.isEmpty()) {
            scheduleRetry();
        }
    }

    private FeedMedia loadMedia(long mediaId) {
        try {
            return database.getMedia(mediaId);
        } catch (SQLException e) {
            return null;
        }
    }

    private boolean isProtected(long mediaId, Set<Long> protectedIds) {
        return isCaching(mediaId) || protectedIds.contains(mediaId);
    }

    private Set<Long> currentProtectedIds() {
        try {
            Set<Long> ids = protectedIdsSupplier.get();
            return ids != null ? ids : Set.of();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private List<FeedMedia> cachedMedia() {
        try {
            return database.getCachedMedia();
        } catch (SQLException e) {
            report("Could not read episode cache: " + e.getMessage());
            return List.of();
        }
    }

    private static long fileLength(String path) {
        File file = path == null ? null : new File(path);
        return file != null && file.isFile() ? file.length() : 0;
    }

    /** Most recent sign of use: playback updates the statistics clock, prefetched files only their mtime. */
    private static long lastUsed(FeedMedia media) {
        return Math.max(media.getLastPlayedTimeStatistics(), new File(media.getCacheFileUrl()).lastModified());
    }

    private static long cacheSize(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return 0;
        }
        long total = 0;
        for (File entry : entries) {
            total += entry.isDirectory() ? cacheSize(entry) : entry.length();
        }
        return total;
    }

    private static int countFiles(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return 0;
        }
        int count = 0;
        for (File entry : entries) {
            count += entry.isDirectory() ? countFiles(entry) : 1;
        }
        return count;
    }

    private void report(String message) {
        Consumer<String> reporter = statusReporter;
        if (reporter != null) {
            try {
                reporter.accept(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
