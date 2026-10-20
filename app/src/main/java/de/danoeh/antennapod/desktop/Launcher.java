package de.danoeh.antennapod.desktop;

public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        // The installed jar carries Implementation-Version. A run from Gradle classes has none,
        // in which case DesktopHttp announces "dev" rather than a version from months ago.
        String version = Launcher.class.getPackage().getImplementationVersion();
        if (version != null && !version.isEmpty()) {
            System.setProperty("antennapod.desktop.version", version);
        }
        // first: the shell resolves taskbar grouping, toasts and the media card against
        // the process identity, so claim it before any window exists
        AppIdentity.apply();
        // before DesktopApp loads: its static labels are the first text lookups
        Messages.applyLanguage(DesktopPreferences.getLanguage());
        DesktopApp.main(args);
    }
}
