package de.danoeh.antennapod.desktop;

import java.io.File;
import java.util.prefs.Preferences;

public final class DesktopPreferences {
    private static final Preferences PREFS = Preferences.userNodeForPackage(DesktopPreferences.class);

    private DesktopPreferences() {
    }

    public static File getDataDir() {
        String override = System.getProperty("antennapod.desktop.dataDir");
        if (override != null && !override.isEmpty()) {
            return new File(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        File base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = new File(appData != null ? appData : System.getProperty("user.home"), "AntennaPod");
        } else {
            base = new File(System.getProperty("user.home"), ".antennapod");
        }
        return base;
    }

    public static File getMediaDir() {
        return new File(getDataDir(), "media");
    }

    public static File getCacheDir() {
        return new File(getDataDir(), "cache");
    }

    public static File getEpisodeCacheDir() {
        return new File(getCacheDir(), "episodes");
    }

    public static File getDatabaseFile() {
        return new File(getDataDir(), "antennapod.db");
    }

    public static float getPlaybackSpeed() {
        return PREFS.getFloat("playbackSpeed", 1.0f);
    }

    public static void setPlaybackSpeed(float speed) {
        PREFS.putFloat("playbackSpeed", speed);
    }

    public static long getLastPlayedMediaId() {
        return PREFS.getLong("lastPlayedMediaId", -1);
    }

    public static void setLastPlayedMediaId(long id) {
        PREFS.putLong("lastPlayedMediaId", id);
    }

    public static String getSyncProvider() {
        return PREFS.get("syncProvider", "none");
    }

    public static void setSyncProvider(String provider) {
        PREFS.put("syncProvider", provider);
    }

    public static String getSyncHost() {
        return PREFS.get("syncHost", "gpodder.net");
    }

    public static void setSyncHost(String host) {
        PREFS.put("syncHost", host);
    }

    public static String getSyncUsername() {
        return PREFS.get("syncUsername", "");
    }

    public static void setSyncUsername(String username) {
        PREFS.put("syncUsername", username);
    }

    public static String getSyncPassword() {
        return PREFS.get("syncPassword", "");
    }

    public static void setSyncPassword(String password) {
        PREFS.put("syncPassword", password);
    }

    public static String getSyncDeviceId() {
        String id = PREFS.get("syncDeviceId", null);
        if (id == null) {
            id = "desktop-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            PREFS.put("syncDeviceId", id);
        }
        return id;
    }

    public static String getSyncDeviceCaption() {
        return PREFS.get("syncDeviceCaption", "AntennaPod Desktop");
    }

    public static void setSyncDeviceCaption(String caption) {
        PREFS.put("syncDeviceCaption", caption);
    }

    public static boolean isSyncEnabled() {
        return !"none".equals(getSyncProvider()) && !getSyncUsername().isEmpty();
    }

    public static long getLastSyncTime() {
        return PREFS.getLong("lastSyncTime", 0);
    }

    public static void setLastSyncTime(long timeMs) {
        PREFS.putLong("lastSyncTime", timeMs);
    }

    public static boolean getAutoSyncPlayback() {
        return PREFS.getBoolean("autoSyncPlayback", true);
    }

    public static void setAutoSyncPlayback(boolean enabled) {
        PREFS.putBoolean("autoSyncPlayback", enabled);
    }

    public static boolean getAutoDownloadDefault() {
        return PREFS.getBoolean("autoDownloadDefault", false);
    }

    public static void setAutoDownloadDefault(boolean enabled) {
        PREFS.putBoolean("autoDownloadDefault", enabled);
    }

    public static boolean getAutoDeleteDefault() {
        return PREFS.getBoolean("autoDeleteDefault", false);
    }

    public static void setAutoDeleteDefault(boolean enabled) {
        PREFS.putBoolean("autoDeleteDefault", enabled);
    }

    public static boolean getEpisodeCacheEnabled() {
        return PREFS.getBoolean("episodeCacheEnabled", true);
    }

    public static void setEpisodeCacheEnabled(boolean enabled) {
        PREFS.putBoolean("episodeCacheEnabled", enabled);
    }

    public static boolean getEpisodeCacheRemoveAfterFinish() {
        return PREFS.getBoolean("episodeCacheRemoveAfterFinish", true);
    }

    public static void setEpisodeCacheRemoveAfterFinish(boolean enabled) {
        PREFS.putBoolean("episodeCacheRemoveAfterFinish", enabled);
    }

    public static int getEpisodeCacheLimitMb() {
        return PREFS.getInt("episodeCacheLimitMb", 2048);
    }

    public static void setEpisodeCacheLimitMb(int megabytes) {
        PREFS.putInt("episodeCacheLimitMb", Math.max(megabytes, 0));
    }

    public static int getEpisodeCachePrefetchCount() {
        return PREFS.getInt("episodeCachePrefetchCount", 2);
    }

    public static void setEpisodeCachePrefetchCount(int count) {
        PREFS.putInt("episodeCachePrefetchCount", Math.max(0, Math.min(count, 10)));
    }

    /** A release the user chose to pass over, so it is not offered again. */
    public static String getSkippedUpdateVersion() {
        return PREFS.get("skippedUpdateVersion", "");
    }

    public static void setSkippedUpdateVersion(String version) {
        PREFS.put("skippedUpdateVersion", version == null ? "" : version);
    }

    public static boolean getUpdateCheckEnabled() {
        return PREFS.getBoolean("updateCheckEnabled", true);
    }

    public static void setUpdateCheckEnabled(boolean enabled) {
        PREFS.putBoolean("updateCheckEnabled", enabled);
    }

    /** On by default: closing the window during an episode should not end it. */
    public static boolean getCloseToTray() {
        return PREFS.getBoolean("closeToTray", true);
    }

    public static void setCloseToTray(boolean enabled) {
        PREFS.putBoolean("closeToTray", enabled);
    }

    /** Whether the keyboard's media keys control this app even when another window has focus. */
    public static boolean getMediaKeysEnabled() {
        return PREFS.getBoolean("mediaKeysEnabled", true);
    }

    public static void setMediaKeysEnabled(boolean enabled) {
        PREFS.putBoolean("mediaKeysEnabled", enabled);
    }

    /** Whether the listener has already been told once that closing leaves the app in the tray. */
    public static boolean getTrayHintShown() {
        return PREFS.getBoolean("trayHintShown", false);
    }

    public static void setTrayHintShown(boolean shown) {
        PREFS.putBoolean("trayHintShown", shown);
    }

    public static String getThemeMode() {
        return PREFS.get("themeMode", "auto");
    }

    public static void setThemeMode(String mode) {
        PREFS.put("themeMode", mode);
    }

    public static String getSleepTimerMode() {
        return PREFS.get("sleepTimerMode", "off");
    }

    public static void setSleepTimerMode(String mode) {
        PREFS.put("sleepTimerMode", mode);
    }

    public static long getSleepTimerDeadline() {
        return PREFS.getLong("sleepTimerDeadline", 0);
    }

    public static void setSleepTimerDeadline(long deadlineMs) {
        PREFS.putLong("sleepTimerDeadline", deadlineMs);
    }

    public static double getDefaultVolume() {
        return PREFS.getDouble("defaultVolume", 1.0);
    }

    public static void setDefaultVolume(double volume) {
        PREFS.putDouble("defaultVolume", volume);
    }

    public static int getSkipIntroSec() {
        return PREFS.getInt("skipIntroSec", 0);
    }

    public static void setSkipIntroSec(int seconds) {
        PREFS.putInt("skipIntroSec", Math.max(seconds, 0));
    }

    public static int getSkipEndingSec() {
        return PREFS.getInt("skipEndingSec", 0);
    }

    public static void setSkipEndingSec(int seconds) {
        PREFS.putInt("skipEndingSec", Math.max(seconds, 0));
    }

    public static int getSkipBackSec() {
        return PREFS.getInt("skipBackSec", 10);
    }

    public static void setSkipBackSec(int seconds) {
        PREFS.putInt("skipBackSec", Math.max(seconds, 0));
    }

    public static int getSkipForwardSec() {
        return PREFS.getInt("skipForwardSec", 30);
    }

    public static void setSkipForwardSec(int seconds) {
        PREFS.putInt("skipForwardSec", Math.max(seconds, 0));
    }

    public static int getVolumeBoostDb() {
        return PREFS.getInt("volumeBoostDb", 0);
    }

    public static void setVolumeBoostDb(int db) {
        PREFS.putInt("volumeBoostDb", Math.max(0, Math.min(db, 12)));
    }

    public static boolean getSkipSilence() {
        return PREFS.getBoolean("skipSilence", false);
    }

    public static void setSkipSilence(boolean enabled) {
        PREFS.putBoolean("skipSilence", enabled);
    }

    public static boolean getAutoRefreshStartup() {
        return PREFS.getBoolean("autoRefreshStartup", false);
    }

    public static void setAutoRefreshStartup(boolean enabled) {
        PREFS.putBoolean("autoRefreshStartup", enabled);
    }

    public static int getAutoRefreshMinutes() {
        return PREFS.getInt("autoRefreshMinutes", 0);
    }

    public static void setAutoRefreshMinutes(int minutes) {
        PREFS.putInt("autoRefreshMinutes", Math.max(minutes, 0));
    }

    public static String getProxyHost() {
        return PREFS.get("proxyHost", "");
    }

    public static void setProxyHost(String host) {
        PREFS.put("proxyHost", host);
    }

    public static int getProxyPort() {
        return PREFS.getInt("proxyPort", 8080);
    }

    public static void setProxyPort(int port) {
        PREFS.putInt("proxyPort", port);
    }

    public static String getProxyUser() {
        return PREFS.get("proxyUser", "");
    }

    public static void setProxyUser(String user) {
        PREFS.put("proxyUser", user);
    }

    public static String getProxyPassword() {
        return PREFS.get("proxyPassword", "");
    }

    public static void setProxyPassword(String password) {
        PREFS.put("proxyPassword", password);
    }
}
