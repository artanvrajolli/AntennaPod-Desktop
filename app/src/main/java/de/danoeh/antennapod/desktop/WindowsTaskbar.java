package de.danoeh.antennapod.desktop;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.stage.Stage;

/**
 * Playback state on the Windows taskbar button: how far through the episode you are, and whether
 * it is playing or paused. Windows 11 draws this as a thin bar under the app icon, Windows 10 as a
 * fill behind it.
 *
 * <p>The JDK has {@code java.awt.Taskbar}, but every feature Windows supports there takes an AWT
 * {@code Window}, and a JavaFX {@code Stage} is not one — so the shell interface behind it,
 * {@code ITaskbarList3}, is called directly instead.
 *
 * <p>Nothing here throws at the caller. Off Windows, without JNA, or if the shell refuses, every
 * method quietly does nothing, so callers never have to ask whether it is available. Starting with
 * {@code -Dantennapod.desktop.taskbar=false} turns it off.
 */
public final class WindowsTaskbar {
    private static final String CLSID_TASKBAR_LIST = "{56FDF344-FD6D-11D0-958A-006097C9A090}";
    private static final String IID_TASKBAR_LIST_3 = "{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}";
    private static final int CLSCTX_INPROC_SERVER = 1;
    private static final int COINIT_APARTMENTTHREADED = 2;

    /** TBPFLAG: the states the progress fill can be in. */
    private static final int TBPF_NOPROGRESS = 0;
    private static final int TBPF_NORMAL = 2;
    private static final int TBPF_PAUSED = 8;

    private static final int ATTACH_ATTEMPTS = 40;
    private static final long ATTACH_RETRY_MS = 250;

    /**
     * The shell object belongs to the apartment that created it, so every call has to come from
     * the same thread. One thread owns it from CoInitializeEx to release.
     */
    private final ExecutorService comThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "windows-taskbar");
        thread.setDaemon(true);
        return thread;
    });

    private volatile TaskbarList3 taskbarList;
    private volatile HWND hwnd;
    /** Last percentage pushed to the shell, so a position update every frame is not a COM call. */
    private volatile int lastPercent = -1;
    private volatile int lastState = -1;
    private volatile ThumbBar thumbBar;

    public static boolean isEnabled() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win")
                && !"false".equalsIgnoreCase(System.getProperty("antennapod.desktop.taskbar", "true"));
    }

    /**
     * Finds the window and hooks it up to the shell. Call once the stage is showing: the window
     * has to exist before it can be found.
     */
    public void attach(Stage stage, ThumbBar.Callbacks controls) {
        if (!isEnabled()) {
            return;
        }
        String title = stage.getTitle();
        submit(() -> {
            try {
                // show() returns before the native window is mapped, so it may not be findable yet
                HWND window = awaitWindow(title);
                if (window == null) {
                    System.err.println("Taskbar: no window found titled " + title);
                    return;
                }
                Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED);
                PointerByReference created = new PointerByReference();
                HRESULT result = Ole32.INSTANCE.CoCreateInstance(
                        new Guid.CLSID(CLSID_TASKBAR_LIST), null, CLSCTX_INPROC_SERVER,
                        new Guid.IID(IID_TASKBAR_LIST_3), created);
                if (result.intValue() < 0) {
                    return;
                }
                TaskbarList3 list = new TaskbarList3(created.getValue());
                if (list.hrInit().intValue() < 0) {
                    list.Release();
                    return;
                }
                hwnd = window;
                taskbarList = list;
                // the window procedure belongs to the thread that created the window, so the
                // subclassing is done there; the COM half of it comes back to this thread
                javafx.application.Platform.runLater(
                        () -> thumbBar = ThumbBar.install(window, list, controls, this::submit));
            } catch (Throwable t) {
                // an unavailable shell interface is not worth breaking playback over
                t.printStackTrace();
            }
        });
    }

    /** Draws how far through the episode we are. A zero or unknown duration clears the fill. */
    public void setProgress(int positionMs, int durationMs) {
        if (taskbarList == null) {
            return;
        }
        int percent = durationMs > 0
                ? (int) Math.max(0, Math.min(100, (long) positionMs * 100 / durationMs)) : -1;
        if (percent == lastPercent) {
            return;
        }
        lastPercent = percent;
        int value = percent;
        run(list -> {
            if (value < 0) {
                list.setProgressState(hwnd, TBPF_NOPROGRESS);
            } else {
                list.setProgressValue(hwnd, value, 100);
            }
        });
    }

    /**
     * Playing uses the shell's normal progress colour, paused its paused colour, and nothing
     * loaded clears the bar — the same vocabulary the shell uses for downloads, so it reads
     * without explanation.
     */
    public void setPlaybackState(boolean loaded, boolean playing) {
        if (taskbarList == null) {
            return;
        }
        int state = !loaded ? TBPF_NOPROGRESS : playing ? TBPF_NORMAL : TBPF_PAUSED;
        if (state == lastState) {
            return;
        }
        lastState = state;
        if (state == TBPF_NOPROGRESS) {
            lastPercent = -1;
        }
        ThumbBar bar = thumbBar;
        if (bar != null) {
            bar.setPlaying(loaded && playing);
        }
        run(list -> list.setProgressState(hwnd, state));
    }

    /** Reflects the skip-silence toggle on the thumbnail toolbar's fourth button. */
    public void setSilenceSkipping(boolean enabled) {
        if (taskbarList == null) {
            return;
        }
        ThumbBar bar = thumbBar;
        if (bar != null) {
            bar.setSilenceSkipping(enabled);
        }
    }

    public void shutdown() {
        submit(() -> {
            ThumbBar bar = thumbBar;
            thumbBar = null;
            if (bar != null) {
                bar.dispose();
            }
            TaskbarList3 list = taskbarList;
            taskbarList = null;
            if (list != null) {
                try {
                    list.setProgressState(hwnd, TBPF_NOPROGRESS);
                    list.Release();
                } catch (Throwable t) {
                    // shutting down anyway
                }
            }
            try {
                Ole32.INSTANCE.CoUninitialize();
            } catch (Throwable t) {
                // shutting down anyway
            }
        });
        comThread.shutdown();
        try {
            comThread.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(java.util.function.Consumer<TaskbarList3> action) {
        submit(() -> {
            TaskbarList3 list = taskbarList;
            if (list == null || hwnd == null) {
                return;
            }
            try {
                action.accept(list);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /**
     * Hands work to the COM thread, ignoring anything submitted after shutdown. The window can be
     * closed while playback is still pushing position updates, so that race is normal.
     */
    private void submit(Runnable action) {
        try {
            comThread.submit(action);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // already shut down
        }
    }

    /** Waits for the window to appear, since show() returns before the native window is mapped. */
    private static HWND awaitWindow(String title) {
        for (int attempt = 0; attempt < ATTACH_ATTEMPTS; attempt++) {
            HWND window = findWindow(title);
            if (window != null) {
                return window;
            }
            try {
                Thread.sleep(ATTACH_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Our own top-level window, found by walking this process's windows rather than reaching into
     * JavaFX internals — which would need --add-exports wherever the app is launched from.
     */
    static HWND findWindow(String title) {
        int pid = (int) ProcessHandle.current().pid();
        HWND[] found = new HWND[1];
        User32.INSTANCE.EnumWindows((window, data) -> {
            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, windowPid);
            if (windowPid.getValue() != pid || !User32.INSTANCE.IsWindowVisible(window)) {
                return true;
            }
            char[] buffer = new char[512];
            User32.INSTANCE.GetWindowText(window, buffer, buffer.length);
            if (title != null && title.equals(Native.toString(buffer))) {
                found[0] = window;
                return false;
            }
            return true;
        }, null);
        return found[0];
    }

    /**
     * ITaskbarList3, called through its vtable. JNA ships no binding for it, and the slot numbers
     * are fixed by the interface: 0-2 are IUnknown, 3-7 ITaskbarList, 8 ITaskbarList2, 9 onwards
     * ITaskbarList3.
     */
    static final class TaskbarList3 extends Unknown {
        private static final int VTBL_HR_INIT = 3;
        private static final int VTBL_SET_PROGRESS_VALUE = 9;
        private static final int VTBL_SET_PROGRESS_STATE = 10;
        private static final int VTBL_THUMB_BAR_ADD_BUTTONS = 15;
        private static final int VTBL_THUMB_BAR_UPDATE_BUTTONS = 16;

        TaskbarList3(Pointer instance) {
            super(instance);
        }

        HRESULT hrInit() {
            return (HRESULT) _invokeNativeObject(VTBL_HR_INIT,
                    new Object[]{getPointer()}, HRESULT.class);
        }

        HRESULT setProgressValue(HWND window, long completed, long total) {
            return (HRESULT) _invokeNativeObject(VTBL_SET_PROGRESS_VALUE,
                    new Object[]{getPointer(), window, completed, total}, HRESULT.class);
        }

        HRESULT setProgressState(HWND window, int flags) {
            return (HRESULT) _invokeNativeObject(VTBL_SET_PROGRESS_STATE,
                    new Object[]{getPointer(), window, flags}, HRESULT.class);
        }

        /** Only ever succeeds once per window; every later change goes through update. */
        HRESULT thumbBarAddButtons(HWND window, int count, Pointer buttons) {
            return (HRESULT) _invokeNativeObject(VTBL_THUMB_BAR_ADD_BUTTONS,
                    new Object[]{getPointer(), window, count, buttons}, HRESULT.class);
        }

        HRESULT thumbBarUpdateButtons(HWND window, int count, Pointer buttons) {
            return (HRESULT) _invokeNativeObject(VTBL_THUMB_BAR_UPDATE_BUTTONS,
                    new Object[]{getPointer(), window, count, buttons}, HRESULT.class);
        }
    }
}
