package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    public synchronized void enqueue(FeedMedia media, ProgressListener listener) {
        if (isDownloading(media.getId())) {
            return;
        }
        running.put(media.getId(), executor.submit(() -> download(media, listener)));
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

    private void download(FeedMedia media, ProgressListener listener) {
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
            running.remove(media.getId());
        }
    }

    /**
     * Streams the episode to {@code target}. The bytes land in a {@code .part} file first and are
     * moved into place once complete, so an interrupted transfer never leaves a half file behind
     * that later looks like a finished download.
     */
    static void fetchToFile(FeedMedia media, File target, ProgressListener listener) throws IOException {
        File part = new File(target.getAbsolutePath() + ".part");
        Request request = new Request.Builder().url(media.getDownloadUrl()).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed: " + response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response");
            }
            long total = body.contentLength();
            target.getParentFile().mkdirs();
            long bytesRead = 0;
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
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
