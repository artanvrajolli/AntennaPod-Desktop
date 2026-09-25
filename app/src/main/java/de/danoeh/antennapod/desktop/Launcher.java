package de.danoeh.antennapod.desktop;

public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        // first: the shell resolves taskbar grouping, toasts and the media card against
        // the process identity, so claim it before any window exists
        AppIdentity.apply();
        DesktopApp.main(args);
    }
}
