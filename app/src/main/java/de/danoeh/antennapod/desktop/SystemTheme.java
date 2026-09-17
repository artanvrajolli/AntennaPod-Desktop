package de.danoeh.antennapod.desktop;

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

    private static boolean isDarkWindows() throws Exception {
        String output = run("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme");
        int index = output.indexOf("REG_DWORD");
        if (index < 0) {
            return false;
        }
        return output.substring(index + "REG_DWORD".length()).trim().startsWith("0x0");
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
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return output.toString();
    }
}
