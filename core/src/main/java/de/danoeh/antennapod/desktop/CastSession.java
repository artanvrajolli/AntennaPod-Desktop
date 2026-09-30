package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One episode playing on a DLNA renderer: what is served to it, the commands sent to it, and a
 * one-second poll of its position and state for the player bar. All renderer calls run on the
 * session's own thread; the listener is called from there too.
 */
public final class CastSession implements AutoCloseable {
    public interface Listener {
        void onProgress(int positionMs, int durationMs, boolean playing);

        /** The episode played to its end on the renderer. */
        void onFinished();

        void onError(String message);
    }

    /** A stop this close to the end counts as the episode having finished. */
    static final int END_MARGIN_MS = 5_000;

    private final DlnaRenderer renderer;
    private final CastServer server;
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cast-session");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> poll;
    private volatile FeedMedia media;
    private volatile int lastPositionMs;
    private volatile int lastDurationMs;
    private volatile boolean startedPlaying;
    private volatile boolean closed;

    public CastSession(DlnaRenderer renderer, CastServer server, Listener listener) {
        this.renderer = renderer;
        this.server = server;
        this.listener = listener;
    }

    public DlnaRenderer renderer() {
        return renderer;
    }

    public FeedMedia media() {
        return media;
    }

    /** Where the renderer last was, to store as the episode's position when casting ends. */
    public int lastPositionMs() {
        return lastPositionMs;
    }

    /** Sends an episode to the renderer and starts it at {@code startMs}. */
    public void cast(FeedMedia episode, int startMs) {
        media = episode;
        lastPositionMs = Math.max(0, startMs);
        lastDurationMs = Math.max(0, episode.getDuration());
        startedPlaying = false;
        run(() -> {
            File file = episode.localFileAvailable() && episode.getLocalFileUrl() != null
                    ? new File(episode.getLocalFileUrl()) : null;
            String url = server.publish(file, episode.getDownloadUrl(), episode.getMimeType(), renderer.host());
            FeedItem item = episode.getItem();
            renderer.load(url, item != null ? item.getTitle() : null, episode.getMimeType());
            renderer.play();
            if (startMs > 1000) {
                // many renderers refuse a seek until the stream has started
                waitUntilPlaying(8_000);
                renderer.seek(startMs);
            }
            startPolling();
        });
    }

    public void pause() {
        run(renderer::pause);
    }

    public void resume() {
        run(renderer::play);
    }

    public void seek(int positionMs) {
        lastPositionMs = Math.max(0, positionMs);
        run(() -> renderer.seek(positionMs));
    }

    public void setVolume(int percent) {
        run(() -> renderer.setVolume(percent));
    }

    private void waitUntilPlaying(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !"PLAYING".equals(renderer.state())) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void startPolling() {
        if (poll != null) {
            poll.cancel(false);
        }
        poll = worker.scheduleWithFixedDelay(this::pollOnce, 1, 1, TimeUnit.SECONDS);
    }

    private void pollOnce() {
        if (closed) {
            return;
        }
        try {
            String state = renderer.state();
            int[] position = renderer.position();
            boolean playing = "PLAYING".equals(state);
            startedPlaying |= playing;
            if (position[0] >= 0) {
                lastPositionMs = position[0];
            }
            if (position[1] > 0) {
                lastDurationMs = position[1];
            }
            listener.onProgress(lastPositionMs, lastDurationMs, playing);
            if (startedPlaying && isFinished(state, lastPositionMs, lastDurationMs)) {
                poll.cancel(false);
                listener.onFinished();
            }
        } catch (IOException e) {
            listener.onError(e.getMessage());
        }
    }

    /** Stopped (or out of media) at the end, rather than stopped halfway on the renderer's remote. */
    static boolean isFinished(String state, int positionMs, int durationMs) {
        boolean stopped = "STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state);
        return stopped && durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS;
    }

    private interface Step {
        void run() throws IOException;
    }

    private void run(Step step) {
        if (closed) {
            return;
        }
        worker.execute(() -> {
            try {
                step.run();
            } catch (IOException e) {
                listener.onError(e.getMessage());
            }
        });
    }

    /** Stops the renderer and takes the episode off the server. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (poll != null) {
            poll.cancel(false);
        }
        worker.execute(() -> {
            try {
                renderer.stop();
            } catch (IOException e) {
                // it may be switched off already; the session ends either way
            }
            server.unpublishAll();
        });
        worker.shutdown();
    }
}
