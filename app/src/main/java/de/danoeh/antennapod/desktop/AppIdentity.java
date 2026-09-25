package de.danoeh.antennapod.desktop;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinNT.HRESULT;

/**
 * The app's explicit identity towards the Windows shell.
 *
 * <p>Without it the process is known to the shell only by its exe path, so the system media
 * card shows "Unknown app" and withholds parts of the timeline UI. Setting the id up front lets
 * taskbar grouping, toasts and the media card resolve to the app instead.
 *
 * <p>The id must never change: the installed shortcuts carry the same value as their
 * {@code System.AppUserModel.ID} (stamped at install time, see {@code main.wxs}), and a window
 * whose id does not match its shortcut groups apart from the pinned icon. Keep
 * {@link #APP_ID} and the installer stamp in step.
 *
 * <p>Best effort throughout: off Windows, or when switched off with
 * {@code -Dantennapod.desktop.appid=false}, this does nothing.
 */
public final class AppIdentity {
    /**
     * The Application User Model ID. Plain alphanumerics and dots only, no version in it: the
     * installed shortcuts keep pointing at it across releases.
     */
    public static final String APP_ID = "AntennaPod.AntennaPodDesktop";

    private AppIdentity() {
    }

    public static boolean isEnabled() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win")
                && !"false".equalsIgnoreCase(System.getProperty("antennapod.desktop.appid", "true"));
    }

    /**
     * Claims {@link #APP_ID} for this process. Call once, first thing in {@code main}: the shell
     * snapshots the identity when windows appear. Returns whether the id stuck; never throws.
     */
    public static boolean apply() {
        if (!isEnabled()) {
            return false;
        }
        try {
            HRESULT hr = Shell32.INSTANCE
                    .SetCurrentProcessExplicitAppUserModelID(new WString(APP_ID));
            return hr.intValue() >= 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
