package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DesktopSettingsTest {
    private float playbackSpeed;
    private int skipIntroSec;
    private int skipEndingSec;
    private int volumeBoostDb;
    private boolean skipSilence;
    private double defaultVolume;
    private boolean autoDownloadDefault;
    private boolean autoDeleteDefault;
    private boolean episodeCacheEnabled;
    private boolean episodeCacheRemoveAfterFinish;
    private int episodeCacheLimitMb;
    private int episodeCachePrefetchCount;
    private boolean autoRefreshStartup;
    private int autoRefreshMinutes;
    private String proxyHost;
    private int proxyPort;
    private String proxyUser;
    private String proxyPassword;
    private String themeMode;

    @Before
    public void snapshotPreferences() {
        playbackSpeed = DesktopPreferences.getPlaybackSpeed();
        skipIntroSec = DesktopPreferences.getSkipIntroSec();
        skipEndingSec = DesktopPreferences.getSkipEndingSec();
        volumeBoostDb = DesktopPreferences.getVolumeBoostDb();
        skipSilence = DesktopPreferences.getSkipSilence();
        defaultVolume = DesktopPreferences.getDefaultVolume();
        autoDownloadDefault = DesktopPreferences.getAutoDownloadDefault();
        autoDeleteDefault = DesktopPreferences.getAutoDeleteDefault();
        episodeCacheEnabled = DesktopPreferences.getEpisodeCacheEnabled();
        episodeCacheRemoveAfterFinish = DesktopPreferences.getEpisodeCacheRemoveAfterFinish();
        episodeCacheLimitMb = DesktopPreferences.getEpisodeCacheLimitMb();
        episodeCachePrefetchCount = DesktopPreferences.getEpisodeCachePrefetchCount();
        autoRefreshStartup = DesktopPreferences.getAutoRefreshStartup();
        autoRefreshMinutes = DesktopPreferences.getAutoRefreshMinutes();
        proxyHost = DesktopPreferences.getProxyHost();
        proxyPort = DesktopPreferences.getProxyPort();
        proxyUser = DesktopPreferences.getProxyUser();
        proxyPassword = DesktopPreferences.getProxyPassword();
        themeMode = DesktopPreferences.getThemeMode();
    }

    @After
    public void restorePreferences() {
        DesktopPreferences.setPlaybackSpeed(playbackSpeed);
        DesktopPreferences.setSkipIntroSec(skipIntroSec);
        DesktopPreferences.setSkipEndingSec(skipEndingSec);
        DesktopPreferences.setVolumeBoostDb(volumeBoostDb);
        DesktopPreferences.setSkipSilence(skipSilence);
        DesktopPreferences.setDefaultVolume(defaultVolume);
        DesktopPreferences.setAutoDownloadDefault(autoDownloadDefault);
        DesktopPreferences.setAutoDeleteDefault(autoDeleteDefault);
        DesktopPreferences.setEpisodeCacheEnabled(episodeCacheEnabled);
        DesktopPreferences.setEpisodeCacheRemoveAfterFinish(episodeCacheRemoveAfterFinish);
        DesktopPreferences.setEpisodeCacheLimitMb(episodeCacheLimitMb);
        DesktopPreferences.setEpisodeCachePrefetchCount(episodeCachePrefetchCount);
        DesktopPreferences.setAutoRefreshStartup(autoRefreshStartup);
        DesktopPreferences.setAutoRefreshMinutes(autoRefreshMinutes);
        DesktopPreferences.setProxyHost(proxyHost);
        DesktopPreferences.setProxyPort(proxyPort);
        DesktopPreferences.setProxyUser(proxyUser);
        DesktopPreferences.setProxyPassword(proxyPassword);
        DesktopPreferences.setThemeMode(themeMode);
    }

    @Test
    public void testPlaybackSettingsRoundTrip() {
        DesktopPreferences.setPlaybackSpeed(1.5f);
        DesktopPreferences.setSkipIntroSec(30);
        DesktopPreferences.setSkipEndingSec(20);
        DesktopPreferences.setVolumeBoostDb(6);
        DesktopPreferences.setSkipSilence(true);
        DesktopPreferences.setDefaultVolume(0.8);
        assertEquals(1.5f, DesktopPreferences.getPlaybackSpeed(), 0.001);
        assertEquals(30, DesktopPreferences.getSkipIntroSec());
        assertEquals(20, DesktopPreferences.getSkipEndingSec());
        assertEquals(6, DesktopPreferences.getVolumeBoostDb());
        assertTrue(DesktopPreferences.getSkipSilence());
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
    public void testEpisodeCacheSettings() {
        DesktopPreferences.setEpisodeCacheEnabled(false);
        DesktopPreferences.setEpisodeCacheRemoveAfterFinish(false);
        DesktopPreferences.setEpisodeCacheLimitMb(512);
        DesktopPreferences.setEpisodeCachePrefetchCount(3);

        assertFalse(DesktopPreferences.getEpisodeCacheEnabled());
        assertFalse(DesktopPreferences.getEpisodeCacheRemoveAfterFinish());
        assertEquals(512, DesktopPreferences.getEpisodeCacheLimitMb());
        assertEquals(3, DesktopPreferences.getEpisodeCachePrefetchCount());

        DesktopPreferences.setEpisodeCacheEnabled(true);
        DesktopPreferences.setEpisodeCacheRemoveAfterFinish(true);
        DesktopPreferences.setEpisodeCacheLimitMb(2048);
        DesktopPreferences.setEpisodeCachePrefetchCount(2);
    }

    @Test
    public void testEpisodeCacheValuesAreClamped() {
        Preferences.userNodeForPackage(DesktopPreferences.class).remove("episodeCacheLimitMb");
        Preferences.userNodeForPackage(DesktopPreferences.class).remove("episodeCachePrefetchCount");
        assertEquals(2048, DesktopPreferences.getEpisodeCacheLimitMb());
        assertEquals(2, DesktopPreferences.getEpisodeCachePrefetchCount());
        assertTrue(DesktopPreferences.getEpisodeCacheEnabled());

        DesktopPreferences.setEpisodeCacheLimitMb(-10);
        DesktopPreferences.setEpisodeCachePrefetchCount(99);
        DesktopPreferences.setEpisodeCachePrefetchCount(-1);
        assertEquals(0, DesktopPreferences.getEpisodeCacheLimitMb());
        assertEquals(0, DesktopPreferences.getEpisodeCachePrefetchCount());

        DesktopPreferences.setEpisodeCacheLimitMb(2048);
        DesktopPreferences.setEpisodeCachePrefetchCount(2);
    }

    @Test
    public void testThemeModeDefaultsToAutoAndRoundTrips() {
        Preferences.userNodeForPackage(DesktopPreferences.class).remove("themeMode");
        assertEquals("auto", DesktopPreferences.getThemeMode());
        DesktopPreferences.setThemeMode("dark");
        assertEquals("dark", DesktopPreferences.getThemeMode());
        DesktopPreferences.setThemeMode("light");
        assertEquals("light", DesktopPreferences.getThemeMode());
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
