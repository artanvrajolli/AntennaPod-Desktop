package de.danoeh.antennapod.desktop;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class SystemTheme {
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final String PERSONALIZE_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    private SystemTheme() {
    }

    static boolean isDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return isDarkWindows();
            }
            if (os.contains("mac")) {
                return isDarkMac();
            }
            return isDarkLinux();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the Windows shell - taskbar, its thumbnail flyouts, Start - is dark. That is its own
     * setting, separate from the app theme: a dark taskbar with light apps is the Windows 10
     * default. Light when it cannot be read or on other systems.
     */
    static boolean isShellDark() {
        try {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    && isWindowsValueZero("SystemUsesLightTheme");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDarkWindows() {
        return isWindowsValueZero("AppsUseLightTheme");
    }

    /**
     * Reads a Personalize DWORD straight from the registry: this runs on the FX thread (theme
     * previews, thumbnail icons), where starting reg.exe and waiting on it could stall the UI.
     */
    private static boolean isWindowsValueZero(String name) {
        WinReg.HKEY root = WinReg.HKEY_CURRENT_USER;
        if (!Advapi32Util.registryValueExists(root, PERSONALIZE_KEY, name)) {
            return false;
        }
        return Advapi32Util.registryGetIntValue(root, PERSONALIZE_KEY, name) == 0;
    }

    private static boolean isDarkMac() throws Exception {
        return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
    }

    private static boolean isDarkLinux() throws Exception {
        String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (scheme.contains("prefer-dark")) {
            return true;
        }
        if (scheme.contains("prefer-light")) {
            return false;
        }
        String gtkTheme = run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
        if (gtkTheme.toLowerCase(Locale.ROOT).contains("dark")) {
            return true;
        }
        return isDarkKde();
    }

    private static boolean isDarkKde() {
        try {
            File globals = new File(System.getProperty("user.home", ""), ".config/kdeglobals");
            List<String> lines = Files.readAllLines(globals.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("ColorScheme=")) {
                    return line.toLowerCase(Locale.ROOT).contains("dark");
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuffer output = new StringBuffer();
        // read on a thread of its own: readLine blocks until the process exits, so reading here
        // would leave the timeout below with nothing to time out
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // whatever was read so far is the answer
            }
        }, "system-theme-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        reader.join(TimeUnit.SECONDS.toMillis(1));
        return output.toString();
    }
}
