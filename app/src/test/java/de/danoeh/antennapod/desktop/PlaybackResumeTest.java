package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Pressing play with nothing loaded has to pick the last episode back up. This lives in the
 * manager rather than in each button, so the tray, the player bar and the space bar cannot drift
 * apart — the tray used to call straight through and did nothing at all.
 */
public class PlaybackResumeTest {
    private static PlaybackManager idleManager() {
        return new PlaybackManager(null, new PlaybackManager.Listener() {
            @Override
            public void onStateChanged() {
            }

            @Override
            public void onPositionChanged(int positionMs, int durationMs) {
            }

            @Override
            public void onLoadingChanged(boolean loading) {
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    @Test
    public void testPlayWithNothingLoadedAsksToResume() {
        PlaybackManager manager = idleManager();
        try {
            AtomicInteger resumes = new AtomicInteger();
            manager.setResumeLastHandler(resumes::incrementAndGet);
            manager.togglePlayPause();
            assertEquals(1, resumes.get());
            manager.togglePlayPause();
            assertEquals(2, resumes.get());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void testPlayWithNothingLoadedAndNoHandlerIsHarmless() {
        PlaybackManager manager = idleManager();
        try {
            manager.togglePlayPause();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void testTheHandlerCanBeReplaced() {
        PlaybackManager manager = idleManager();
        try {
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            manager.setResumeLastHandler(first::incrementAndGet);
            manager.setResumeLastHandler(second::incrementAndGet);
            manager.togglePlayPause();
            assertEquals(0, first.get());
            assertEquals(1, second.get());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public void testNothingIsLoadedToBeginWith() {
        PlaybackManager manager = idleManager();
        try {
            org.junit.Assert.assertNull(manager.getCurrentMedia());
            org.junit.Assert.assertFalse(manager.isPlaying());
        } finally {
            manager.shutdown();
        }
    }
}
