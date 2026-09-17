package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeManagerTest {
    @Test
    public void testExplicitModesIgnoreSystem() {
        assertTrue(ThemeManager.resolveDark(ThemeManager.MODE_DARK, false));
        assertFalse(ThemeManager.resolveDark(ThemeManager.MODE_LIGHT, true));
    }

    @Test
    public void testAutoModeFollowsSystem() {
        assertTrue(ThemeManager.resolveDark(ThemeManager.MODE_AUTO, true));
        assertFalse(ThemeManager.resolveDark(ThemeManager.MODE_AUTO, false));
    }

    @Test
    public void testUnknownModeFallsBackToSystem() {
        assertTrue(ThemeManager.resolveDark(null, true));
        assertFalse(ThemeManager.resolveDark("sepia", false));
    }

    @Test
    public void testStylesheetsAreBundled() {
        assertNotNull(ThemeManager.LIGHT_STYLESHEET);
        assertNotNull(ThemeManager.DARK_STYLESHEET);
        assertTrue(ThemeManager.stylesheet(true).endsWith("app-dark.css"));
        assertTrue(ThemeManager.stylesheet(false).endsWith("app-light.css"));
    }
}
