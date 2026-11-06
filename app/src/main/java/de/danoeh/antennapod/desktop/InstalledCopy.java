package de.danoeh.antennapod.desktop;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.File;

/**
 * Whether this process runs from the copy the installer put on the PC, and where that is.
 *
 * <p>Only that copy can be updated quietly: the installer is told to install into the same
 * folder, so shortcuts, the pinned taskbar button and "start with Windows" keep pointing at the
 * right exe. A portable zip, a run from source or a copy moved somewhere else gets the setup
 * wizard instead, as before. The installer's entry under Uninstall names the folder it installed
 * to; a per-user install can show up under either hive, so both are looked at.
 */
final class InstalledCopy {
    private static final String UNINSTALL = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    /** jpackage's --name, which the installer uses as the Programs and Features name. */
    private static final String PRODUCT_NAME = "AntennaPod-Desktop";

    private InstalledCopy() {
    }

    /** The install folder holding the running exe, or null when this is not the installed copy. */
    static File installDir() {
        String exe = StartupRegistration.exePath();
        if (exe == null || DesktopPreferences.getPortableDir() != null) {
            return null;
        }
        File exeDir = new File(exe).getAbsoluteFile().getParentFile();
        if (exeDir == null) {
            return null;
        }
        for (WinReg.HKEY hive : new WinReg.HKEY[] {WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE}) {
            try {
                for (String key : Advapi32Util.registryGetKeys(hive, UNINSTALL)) {
                    String path = UNINSTALL + "\\" + key;
                    if (PRODUCT_NAME.equals(stringValue(hive, path, "DisplayName"))
                            && sameFolder(stringValue(hive, path, "InstallLocation"), exeDir)) {
                        return exeDir;
                    }
                }
            } catch (Throwable e) {
                // no registry access: treat it as not installed, the wizard still works
            }
        }
        return null;
    }

    /** Whether an Uninstall entry's InstallLocation names {@code dir}, ignoring case and slashes. */
    static boolean sameFolder(String installLocation, File dir) {
        if (installLocation == null || installLocation.isBlank() || dir == null) {
            return false;
        }
        return normalize(installLocation).equalsIgnoreCase(normalize(dir.getAbsolutePath()));
    }

    private static String normalize(String path) {
        String out = path.trim().replace('/', '\\');
        while (out.endsWith("\\")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String stringValue(WinReg.HKEY hive, String key, String name) {
        try {
            return Advapi32Util.registryValueExists(hive, key, name)
                    ? Advapi32Util.registryGetStringValue(hive, key, name) : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
