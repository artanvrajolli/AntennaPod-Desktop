package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import java.io.File;

public final class DesktopHttp {
    private static File initializedCacheDir;

    private DesktopHttp() {
    }

    public static synchronized void init() {
        File cacheDir = new File(DesktopPreferences.getCacheDir(), "http");
        if (cacheDir.equals(initializedCacheDir)) {
            return;
        }
        cacheDir.mkdirs();
        AntennapodHttpClient.setCacheDirectory(cacheDir);
        UserAgentInterceptor.USER_AGENT = "AntennaPod-Desktop/0.1.0";
        AntennapodHttpClient.reinit();
        initializedCacheDir = cacheDir;
    }
}
