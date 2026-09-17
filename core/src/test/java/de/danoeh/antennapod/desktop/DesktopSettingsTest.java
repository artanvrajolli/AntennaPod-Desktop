package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DesktopSettingsTest {
    @Test
    public void testPlaybackSettingsRoundTrip() {
        DesktopPreferences.setPlaybackSpeed(1.5f);
        DesktopPreferences.setSkipIntroSec(30);
        DesktopPreferences.setSkipEndingSec(20);
        DesktopPreferences.setVolumeBoostDb(6);
        DesktopPreferences.setDefaultVolume(0.8);
        assertEquals(1.5f, DesktopPreferences.getPlaybackSpeed(), 0.001);
        assertEquals(30, DesktopPreferences.getSkipIntroSec());
        assertEquals(20, DesktopPreferences.getSkipEndingSec());
        assertEquals(6, DesktopPreferences.getVolumeBoostDb());
        assertEquals(0.8, DesktopPreferences.getDefaultVolume(), 0.001);
    }

    @Test
    public void testValueClamping() {
        DesktopPreferences.setSkipIntroSec(-5);
        DesktopPreferences.setVolumeBoostDb(99);
        assertEquals(0, DesktopPreferences.getSkipIntroSec());
        assertEquals(12, DesktopPreferences.getVolumeBoostDb());
        DesktopPreferences.setVolumeBoostDb(0);
    }

    @Test
    public void testAutomationAndRefreshSettings() {
        DesktopPreferences.setAutoDownloadDefault(true);
        DesktopPreferences.setAutoDeleteDefault(true);
        DesktopPreferences.setAutoRefreshStartup(true);
        DesktopPreferences.setAutoRefreshMinutes(60);
        assertTrue(DesktopPreferences.getAutoDownloadDefault());
        assertTrue(DesktopPreferences.getAutoDeleteDefault());
        assertTrue(DesktopPreferences.getAutoRefreshStartup());
        assertEquals(60, DesktopPreferences.getAutoRefreshMinutes());
        DesktopPreferences.setAutoDownloadDefault(false);
        DesktopPreferences.setAutoDeleteDefault(false);
        DesktopPreferences.setAutoRefreshStartup(false);
        DesktopPreferences.setAutoRefreshMinutes(0);
    }

    @Test
    public void testProxySettings() {
        DesktopPreferences.setProxyHost("proxy.example.com");
        DesktopPreferences.setProxyPort(3128);
        DesktopPreferences.setProxyUser("user");
        DesktopPreferences.setProxyPassword("secret");
        assertEquals("proxy.example.com", DesktopPreferences.getProxyHost());
        assertEquals(3128, DesktopPreferences.getProxyPort());
        assertEquals("user", DesktopPreferences.getProxyUser());
        assertEquals("secret", DesktopPreferences.getProxyPassword());
        DesktopPreferences.setProxyHost("");
        DesktopPreferences.setProxyUser("");
        DesktopPreferences.setProxyPassword("");
    }
}
