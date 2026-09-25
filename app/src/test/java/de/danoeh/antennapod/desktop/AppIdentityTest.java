package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The shell identity behind the taskbar group and the media card label. The value itself is
 * pinned by contract (shortcuts and code must agree), so the format test runs everywhere and
 * the native call runs where there is a shell to take it.
 */
public class AppIdentityTest {
    private String originalProperty;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win");
    }

    @Before
    public void setUp() {
        originalProperty = System.getProperty("antennapod.desktop.appid");
    }

    @After
    public void tearDown() {
        if (originalProperty == null) {
            System.clearProperty("antennapod.desktop.appid");
        } else {
            System.setProperty("antennapod.desktop.appid", originalProperty);
        }
    }

    @Test
    public void testAppIdIsStableAndValid() {
        // hardcoded on purpose: the installed shortcuts carry this exact value
        assertEquals("AntennaPod.AntennaPodDesktop", AppIdentity.APP_ID);
        assertTrue(AppIdentity.APP_ID.matches("[A-Za-z0-9.]+"));
        assertTrue(AppIdentity.APP_ID.contains("."));
        assertTrue(AppIdentity.APP_ID.length() <= 128);
    }

    @Test
    public void testDisabledByProperty() {
        System.setProperty("antennapod.desktop.appid", "false");
        assertFalse(AppIdentity.isEnabled());
        assertFalse(AppIdentity.apply());
    }

    @Test
    public void testApplySticksOnWindowsAndNeverThrows() {
        // elsewhere this is a no-op below the native call
        boolean applied = AppIdentity.apply();
        if (isWindows()) {
            assertTrue(applied);
            assertTrue(AppIdentity.isEnabled());
        } else {
            assertFalse(applied);
        }
    }
}
