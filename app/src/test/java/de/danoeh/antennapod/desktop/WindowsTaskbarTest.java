package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

/**
 * The taskbar integration has to be harmless when it cannot work: off Windows, when the shell
 * refuses the interface, or before a window has been attached. None of these may throw at the
 * playback code that calls them on every position update.
 */
public class WindowsTaskbarTest {
    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win");
    }

    @Test
    public void testCallsBeforeAttachDoNothingAndDoNotThrow() {
        WindowsTaskbar taskbar = new WindowsTaskbar();
        try {
            taskbar.setProgress(30_000, 600_000);
            taskbar.setProgress(0, 0);
            taskbar.setPlaybackState(true, true);
            taskbar.setPlaybackState(true, false);
            taskbar.setPlaybackState(false, false);
        } finally {
            taskbar.shutdown();
        }
    }

    @Test
    public void testShutdownIsSafeTwice() {
        WindowsTaskbar taskbar = new WindowsTaskbar();
        taskbar.shutdown();
        taskbar.shutdown();
        // calls after shutdown must not throw either: the window can close mid-playback
        taskbar.setProgress(1000, 2000);
        taskbar.setPlaybackState(true, true);
    }

    @Test
    public void testDisabledBySystemProperty() {
        String previous = System.getProperty("antennapod.desktop.taskbar");
        try {
            System.setProperty("antennapod.desktop.taskbar", "false");
            assertFalse(WindowsTaskbar.isEnabled());
            System.setProperty("antennapod.desktop.taskbar", "true");
            assertEquals(onWindows(), WindowsTaskbar.isEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty("antennapod.desktop.taskbar");
            } else {
                System.setProperty("antennapod.desktop.taskbar", previous);
            }
        }
    }

    @Test
    public void testFindsNoWindowForATitleThatIsNotOurs() {
        Assume.assumeTrue("Windows only", onWindows());
        assertNull(WindowsTaskbar.findWindow("a title no window of this process has"));
    }

    @Test
    public void testEnabledOnlyOnWindows() {
        if (!onWindows()) {
            assertFalse(WindowsTaskbar.isEnabled());
        } else {
            assertTrue(WindowsTaskbar.isEnabled());
        }
    }
}
