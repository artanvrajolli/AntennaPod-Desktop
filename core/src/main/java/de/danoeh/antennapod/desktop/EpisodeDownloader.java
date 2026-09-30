package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class EpisodeDownloader {
    private final DesktopDatabase database;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();

    public EpisodeDownloader(DesktopDatabase database) {
        this.database = database;
        DesktopHttp.init();
    }

    public interface ProgressListener {
        void onProgress(long mediaId, long bytesRead, long totalBytes);

        void onFinished(long mediaId, File file);

        void onError(long mediaId, Exception e);
    }

    public synchronized boolean isDownloading(long mediaId) {
        Future<?> future = running.get(mediaId);
        return future != null && !future.isDone();
    }

    /** The media ids with a transfer running or waiting for a free slot right now. */
    public synchronized List<Long> activeMediaIds() {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Future<?>> entry : running.entrySet()) {
            if (!entry.getValue().isDone()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    public synchronized void enqueue(FeedMedia media, ProgressListener listener) {
        // a local folder's file is already here, and is not the downloader's to fetch or replace
        if (isDownloading(media.getId()) || LocalFolderFeeds.isLocalMedia(media)) {
            return;
        }
        AtomicReference<Future<?>> self = new AtomicReference<>();
        Future<?> future = executor.submit(() -> download(media, listener, self));
        self.set(future);
        running.put(media.getId(), future);
    }

    public synchronized void cancel(long mediaId) {
        Future<?> future = running.get(mediaId);
        if (future != null) {
            future.cancel(true);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void download(FeedMedia media, ProgressListener listener,
            AtomicReference<Future<?>> self) {
        File target = targetFile(media);
        try {
            fetchToFile(media, target, listener);
            media.setLocalFileUrl(target.getAbsolutePath());
            media.setDownloaded(true, System.currentTimeMillis());
            database.setMediaDownloaded(media.getId(), media.getLocalFileUrl(), media.getDownloadDate(),
                    media.getSize());
            listener.onFinished(media.getId(), target);
        } catch (Exception e) {
            target.delete();
            listener.onError(media.getId(), e);
        } finally {
            // only this run's own entry: a cancelled run can still be winding down after the
            // episode was queued again, and must not unregister the new run
            Future<?> mine = self.get();
            if (mine != null) {
                running.remove(media.getId(), mine);
            }
        }
    }

    /** Whether a failure is the transfer being cancelled rather than anything going wrong. */
    public static boolean isCancellation(Exception e) {
        return e instanceof IOException && CANCELLED.equals(e.getMessage());
    }

    private static final String CANCELLED = "Download cancelled";

    /**
     * Streams the episode to {@code target}. The bytes land in a {@code .part} file first and are
     * moved into place once complete, so an interrupted transfer never leaves a half file behind
     * that later looks like a finished download.
     */
    static void fetchToFile(FeedMedia media, File target, ProgressListener listener) throws IOException {
        target.getParentFile().mkdirs();
        // a part file per run: a cancelled run still winding down must not truncate, or delete,
        // the file a new run for the same episode is writing
        File part = File.createTempFile(target.getName() + "-", ".part", target.getParentFile());
        // no-store: the shared client's small HTTP cache would otherwise take a second copy of
        // every episode on the way through, only to evict it again
        Request request = new Request.Builder().url(media.getDownloadUrl())
                .cacheControl(new CacheControl.Builder().noStore().build()).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(Messages.format("error.download.http", response));
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException(Messages.get("error.download.empty"));
            }
            long total = body.contentLength();
            long bytesRead = 0;
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException(CANCELLED);
                    }
                    out.write(buffer, 0, read);
                    bytesRead += read;
                    listener.onProgress(media.getId(), bytesRead, total);
                }
            }
            if (total > 0) {
                media.setSize(total);
            }
            Files.deleteIfExists(target.toPath());
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.InterruptedIOException e) {
            part.delete();
            // cancel() interrupts the thread, and Okio usually notices first, inside read(); a
            // timeout is an InterruptedIOException too, but a real failure
            if (Thread.currentThread().isInterrupted()
                    && !(e instanceof java.net.SocketTimeoutException)) {
                throw new IOException(CANCELLED, e);
            }
            throw e;
        } catch (Exception e) {
            part.delete();
            throw e;
        }
    }

    static File targetFile(FeedMedia media) {
        long feedId = media.getItem() != null ? media.getItem().getFeedId() : 0;
        // the media id keeps episodes whose URLs share a file name (".../media.mp3?id=...") apart;
        // without it one download replaced another and both episodes played the same audio
        return new File(new File(DesktopPreferences.getMediaDir(), String.valueOf(feedId)),
                media.getId() + "-" + fileNameFor(media));
    }

    /** File name used for an episode's local copy, shared by downloads and the playback cache. */
    static String fileNameFor(FeedMedia media) {
        String url = media.getDownloadUrl();
        String name = url == null ? "" : url.substring(url.lastIndexOf('/') + 1);
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        if (name.isEmpty()) {
            name = "episode-" + media.getId();
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
