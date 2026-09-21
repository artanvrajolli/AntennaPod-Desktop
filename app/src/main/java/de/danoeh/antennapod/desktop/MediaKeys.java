package de.danoeh.antennapod.desktop;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;

/**
 * The media keys on a keyboard — play/pause, next, previous and stop — working while another
 * window has the focus.
 *
 * <p>A keyboard's media keys, and anything that synthesises them, do not arrive as ordinary key
 * events. Windows delivers them to whoever claimed them with {@code RegisterHotKey}, and to nobody
 * else, which is why they reach Spotify or a browser but never reached this window.
 *
 * <p>Claiming them needs a message queue to deliver {@code WM_HOTKEY} to. Rather than subclass the
 * main window a second time — the thumbnail toolbar already owns that, and two subclasses that
 * unhook in the wrong order leave the window pointing at a procedure that is gone — this registers
 * the keys against a thread of its own and runs a small message loop there. Nothing else in the
 * app is touched, and stopping it is just asking that loop to quit.
 *
 * <p>A hotkey belongs to one process at a time. If another player already holds one, claiming it
 * fails and that key is left alone rather than fought over; {@link #claimed()} says which ones
 * this actually got. {@code -Dantennapod.desktop.mediakeys=false} claims nothing at all.
 */
final class MediaKeys {
    interface Callbacks {
        void onPlayPause();

        void onNext();

        void onPrevious();

        void onStop();
    }

    /** Virtual key codes for the media keys, from WinUser.h. */
    static final int VK_MEDIA_NEXT_TRACK = 0xB0;
    static final int VK_MEDIA_PREV_TRACK = 0xB1;
    static final int VK_MEDIA_STOP = 0xB2;
    static final int VK_MEDIA_PLAY_PAUSE = 0xB3;

    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_QUIT = 0x0012;
    /** No shift, control or alt: the media keys are claimed on their own. */
    private static final int NO_MODIFIERS = 0;

    /** Hotkey ids, which come back in WM_HOTKEY's wParam. Any small distinct numbers will do. */
    static final int ID_PLAY_PAUSE = 1;
    static final int ID_NEXT = 2;
    static final int ID_PREVIOUS = 3;
    static final int ID_STOP = 4;

    /** Which key each id claims, in the order they are registered. */
    static Map<Integer, Integer> keysById() {
        Map<Integer, Integer> keys = new LinkedHashMap<>();
        keys.put(ID_PLAY_PAUSE, VK_MEDIA_PLAY_PAUSE);
        keys.put(ID_NEXT, VK_MEDIA_NEXT_TRACK);
        keys.put(ID_PREVIOUS, VK_MEDIA_PREV_TRACK);
        keys.put(ID_STOP, VK_MEDIA_STOP);
        return keys;
    }

    /** A name for each key, for the message that says which ones another app already holds. */
    static String nameOf(int id) {
        switch (id) {
            case ID_PLAY_PAUSE: return "play/pause";
            case ID_NEXT: return "next";
            case ID_PREVIOUS: return "previous";
            case ID_STOP: return "stop";
            default: return "unknown";
        }
    }

    private final Callbacks callbacks;
    private Thread thread;
    private volatile int threadId;
    private volatile List<Integer> claimed = List.of();
    private volatile boolean running;

    MediaKeys(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("antennapod.desktop.mediakeys", "true"))
                && System.getProperty("os.name", "").toLowerCase(java.util.Locale.US)
                        .contains("win");
    }

    /** The keys this actually holds. Empty until {@link #start()} has got somewhere. */
    List<Integer> claimed() {
        return claimed;
    }

    boolean isRunning() {
        return running;
    }

    /**
     * Claims the media keys on a thread of its own. Returns straight away; whether any key was
     * free is reported through {@link #claimed()} once the loop is up.
     */
    synchronized void start() {
        if (thread != null || !isEnabled()) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "media-keys");
        thread.setDaemon(true);
        thread.start();
    }

    /** Hands the keys back, so another player can have them again. */
    synchronized void stop() {
        Thread worker = thread;
        running = false;
        thread = null;
        if (worker == null) {
            return;
        }
        int id = threadId;
        if (id != 0) {
            try {
                // the loop unregisters on its way out, on the thread that owns the keys
                Win32.INSTANCE.PostThreadMessage(id, WM_QUIT, new WPARAM(0), new LPARAM(0));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private void run() {
        threadId = Kernel32.INSTANCE.GetCurrentThreadId();
        List<Integer> got = new ArrayList<>();
        try {
            for (Map.Entry<Integer, Integer> entry : keysById().entrySet()) {
                boolean ok = Win32.INSTANCE.RegisterHotKey(null, entry.getKey(), NO_MODIFIERS,
                        entry.getValue());
                if (ok) {
                    got.add(entry.getKey());
                }
            }
            claimed = List.copyOf(got);
            if (got.isEmpty()) {
                // nothing to listen for, so do not sit in a message loop for the app's lifetime
                return;
            }
            pumpMessages();
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            for (int id : got) {
                try {
                    Win32.INSTANCE.UnregisterHotKey(null, id);
                } catch (Throwable t) {
                    // shutting down either way
                }
            }
            claimed = List.of();
            threadId = 0;
            running = false;
        }
    }

    private void pumpMessages() {
        WinUser.MSG message = new WinUser.MSG();
        while (true) {
            int result = Win32.INSTANCE.GetMessage(message, null, 0, 0);
            if (result <= 0) {
                // 0 is WM_QUIT, -1 is an error; either way this loop is done
                return;
            }
            if (message.message == WM_HOTKEY) {
                dispatch(message.wParam.intValue());
            }
        }
    }

    private void dispatch(int id) {
        Runnable action;
        switch (id) {
            case ID_PLAY_PAUSE: action = callbacks::onPlayPause; break;
            case ID_NEXT: action = callbacks::onNext; break;
            case ID_PREVIOUS: action = callbacks::onPrevious; break;
            case ID_STOP: action = callbacks::onStop; break;
            default: return;
        }
        // the message loop is not the application thread, so hand the work over
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /** The user32 calls this needs, which JNA's own bindings do not all cover. */
    interface Win32 extends StdCallLibrary {
        Win32 INSTANCE = Native.load("user32", Win32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean RegisterHotKey(HWND hWnd, int id, int modifiers, int virtualKey);

        boolean UnregisterHotKey(HWND hWnd, int id);

        int GetMessage(WinUser.MSG message, HWND hWnd, int filterMin, int filterMax);

        boolean PostThreadMessage(int threadId, int message, WPARAM wParam, LPARAM lParam);
    }
}
