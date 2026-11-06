package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Test;

public class BackgroundUpdateTest {
    @Test
    public void installLocationMatchesIgnoringCaseAndTrailingSlash() {
        File dir = new File("C:\\Users\\A\\AppData\\Local\\AntennaPod-Desktop");
        assertTrue(InstalledCopy.sameFolder("C:\\Users\\A\\AppData\\Local\\AntennaPod-Desktop\\", dir));
        assertTrue(InstalledCopy.sameFolder("c:/users/a/appdata/local/antennapod-desktop", dir));
    }

    @Test
    public void anotherFolderOrNoLocationIsNotTheInstalledCopy() {
        File dir = new File("D:\\Portable\\AntennaPod-Desktop");
        assertFalse(InstalledCopy.sameFolder("C:\\Users\\A\\AppData\\Local\\AntennaPod-Desktop\\", dir));
        assertFalse(InstalledCopy.sameFolder("", dir));
        assertFalse(InstalledCopy.sameFolder(null, dir));
        assertFalse(InstalledCopy.sameFolder("D:\\Portable\\AntennaPod-Desktop2", dir));
    }

    @Test
    public void sizeTextShowsDownloadedOfTotal() {
        assertEquals("12.4 MB of 96.0 MB", DesktopApp.updateSizeText(12_400_000, 96_000_000));
    }

    @Test
    public void sizeTextWithUnknownTotalShowsOnlyDownloaded() {
        assertEquals("0.5 MB", DesktopApp.updateSizeText(500_000, -1));
    }
}
