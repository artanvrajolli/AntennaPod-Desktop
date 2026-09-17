package de.danoeh.antennapod.desktop;

import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;

public final class ThemeManager {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    static final URL LIGHT_STYLESHEET = resource("/css/app-light.css");
    static final URL DARK_STYLESHEET = resource("/css/app-dark.css");

    private static final long SYSTEM_POLL_SECONDS = 10;

    private static boolean initialized;
    private static boolean darkActive;
    private static boolean lastSystemDark;
    private static ScheduledExecutorService systemWatcher;
    private static ScheduledFuture<?> systemWatchTask;

    private ThemeManager() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    style(window.getScene());
                }
            }
        });
        applySavedMode();
    }

    public static void applySavedMode() {
        String mode = DesktopPreferences.getThemeMode();
        if (MODE_AUTO.equals(mode)) {
            startSystemWatcher();
        } else {
            stopSystemWatcher();
        }
        applyDark(resolveDark(mode, SystemTheme.isDark()));
    }

    public static void previewMode(String mode) {
        applyDark(resolveDark(mode, SystemTheme.isDark()));
    }

    public static boolean isDark() {
        return darkActive;
    }

    public static void style(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.getStylesheets().remove(stylesheet(!darkActive));
        String wanted = stylesheet(darkActive);
        if (!scene.getStylesheets().contains(wanted)) {
            scene.getStylesheets().add(wanted);
        }
    }

    static boolean resolveDark(String mode, boolean systemDark) {
        if (MODE_DARK.equals(mode)) {
            return true;
        }
        if (MODE_LIGHT.equals(mode)) {
            return false;
        }
        return systemDark;
    }

    static String stylesheet(boolean dark) {
        return (dark ? DARK_STYLESHEET : LIGHT_STYLESHEET).toExternalForm();
    }

    private static void applyDark(boolean dark) {
        darkActive = dark;
        for (Window window : Window.getWindows()) {
            style(window.getScene());
        }
    }

    private static void startSystemWatcher() {
        if (systemWatcher != null) {
            return;
        }
        lastSystemDark = SystemTheme.isDark();
        systemWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "system-theme-watcher");
            thread.setDaemon(true);
            return thread;
        });
        systemWatchTask = systemWatcher.scheduleWithFixedDelay(() -> {
            boolean detected = SystemTheme.isDark();
            if (detected == lastSystemDark) {
                return;
            }
            lastSystemDark = detected;
            try {
                Platform.runLater(() -> applyDark(detected));
            } catch (IllegalStateException ignored) {
                return;
            }
        }, SYSTEM_POLL_SECONDS, SYSTEM_POLL_SECONDS, TimeUnit.SECONDS);
    }

    private static void stopSystemWatcher() {
        if (systemWatcher == null) {
            return;
        }
        if (systemWatchTask != null) {
            systemWatchTask.cancel(false);
            systemWatchTask = null;
        }
        systemWatcher.shutdownNow();
        systemWatcher = null;
    }

    private static URL resource(String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("Missing stylesheet: " + path);
        }
        return url;
    }
}
