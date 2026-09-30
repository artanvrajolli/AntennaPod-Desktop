package de.danoeh.antennapod.desktop;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * "Start with Windows": a value under the current user's Run key that starts the exe with
 * {@link #MINIMIZED_ARG}, so it comes up in the tray rather than as a window at every login.
 * Only a real exe can be registered (jpackage names it in {@code jpackage.app-path}); a run from
 * source has nothing stable to point at. Like the other shell integration it degrades to doing
 * nothing when the registry cannot be reached.
 */
final class StartupRegistration {
    static final String MINIMIZED_ARG = "--minimized";
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "AntennaPod Desktop";

    private StartupRegistration() {
    }

    /** The exe this process runs as, or null from source. */
    static String exePath() {
        String exe = System.getProperty("jpackage.app-path");
        return exe != null && !exe.isEmpty() ? exe : null;
    }

    static boolean isAvailable() {
        return exePath() != null;
    }

    /** The Run value that starts {@code exe} into the tray. */
    static String commandFor(String exe) {
        return "\"" + exe + "\" " + MINIMIZED_ARG;
    }

    /** Whether a Run value starts this very exe (a moved portable copy's value does not). */
    static boolean pointsAt(String value, String exe) {
        if (value == null || exe == null) {
            return false;
        }
        String trimmed = value.trim();
        String path = trimmed.startsWith("\"")
                ? trimmed.substring(1, Math.max(1, trimmed.indexOf('"', 1)))
                : trimmed.split("\\s+")[0];
        return path.equalsIgnoreCase(exe);
    }

    static boolean isEnabled() {
        String exe = exePath();
        if (exe == null) {
            return false;
        }
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
                    && pointsAt(Advapi32Util.registryGetStringValue(
                            WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME), exe);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Adds or removes the Run value; false when that did not work. */
    static boolean setEnabled(boolean enabled) {
        String exe = exePath();
        if (exe == null) {
            return false;
        }
        try {
            if (enabled) {
                Advapi32Util.registrySetStringValue(
                        WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, commandFor(exe));
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
