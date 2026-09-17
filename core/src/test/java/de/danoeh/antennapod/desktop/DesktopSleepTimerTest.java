package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DesktopSleepTimerTest {
    @Test
    public void testExpiresAfterDelay() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SleepTimer timer = new SleepTimer(latch::countDown);
        try {
            timer.startMillis(100);
            assertEquals(SleepTimer.Mode.AFTER_MINUTES, timer.getMode());
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(SleepTimer.Mode.OFF, timer.getMode());
        } finally {
            timer.shutdown();
        }
    }

    @Test
    public void testCancelPreventsExpiry() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SleepTimer timer = new SleepTimer(latch::countDown);
        try {
            timer.startMillis(100);
            timer.cancel();
            assertEquals(SleepTimer.Mode.OFF, timer.getMode());
            assertTrue(!latch.await(500, TimeUnit.MILLISECONDS));
        } finally {
            timer.shutdown();
        }
    }

    @Test
    public void testEndOfEpisodeMode() {
        SleepTimer timer = new SleepTimer(() -> {
        });
        try {
            timer.startEndOfEpisode();
            assertEquals(SleepTimer.Mode.END_OF_EPISODE, timer.getMode());
            assertEquals(-1, timer.getRemainingMs());
        } finally {
            timer.shutdown();
        }
    }
}
