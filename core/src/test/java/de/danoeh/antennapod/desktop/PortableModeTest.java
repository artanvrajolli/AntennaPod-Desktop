package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.prefs.Preferences;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PortableModeTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testDataFolderNextToTheExeSwitchesItOn() throws Exception {
        File appDir = tempFolder.newFolder("AntennaPod-Desktop");
        String exe = new File(appDir, "AntennaPod-Desktop.exe").getPath();
        assertNull("without a data folder the app stays installed-style",
                DesktopPreferences.portableDirNextTo(exe));
        File data = new File(appDir, DesktopPreferences.PORTABLE_FOLDER);
        assertTrue(data.mkdir());
        assertEquals(data.getAbsoluteFile(), DesktopPreferences.portableDirNextTo(exe));
        assertNull(DesktopPreferences.portableDirNextTo(null));
    }

    @Test
    public void testSettingsSurviveAReopen() throws Exception {
        File file = new File(tempFolder.getRoot(), "settings.properties");
        Preferences prefs = new FilePreferences(file);
        prefs.put("themeMode", "dark");
        prefs.putInt("skipBackSec", 15);
        prefs.putBoolean("skipSilence", true);
        assertTrue(file.isFile());

        Preferences reopened = new FilePreferences(file);
        assertEquals("dark", reopened.get("themeMode", "auto"));
        assertEquals(15, reopened.getInt("skipBackSec", 10));
        assertTrue(reopened.getBoolean("skipSilence", false));
        assertTrue(Arrays.asList(reopened.keys()).contains("themeMode"));

        reopened.remove("themeMode");
        assertEquals("auto", new FilePreferences(file).get("themeMode", "auto"));
        assertFalse(new File(file.getPath() + ".tmp").exists());
    }

    @Test
    public void testMissingOrBrokenFileStartsEmpty() throws Exception {
        File missing = new File(tempFolder.getRoot(), "none.properties");
        assertEquals("fallback", new FilePreferences(missing).get("anything", "fallback"));
        assertFalse("reading alone creates no file", missing.exists());
    }
}
