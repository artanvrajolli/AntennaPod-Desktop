package de.danoeh.antennapod.desktop;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import javafx.application.Platform;

/**
 * The media buttons under the window's taskbar thumbnail — previous, play/pause and next — which
 * Windows shows when you hover the taskbar button.
 *
 * <p>This is the part of the taskbar integration that has to sit inside the window's message loop:
 * the shell reports a button press as {@code WM_COMMAND}, and it announces that the taskbar button
 * exists with a registered message, which is the only safe moment to add the buttons. So the window
 * procedure is subclassed. Everything else is arranged around keeping that safe — the callback is
 * held in a field so it cannot be collected while Windows still points at it, it passes on every
 * message it does not handle, and it never lets an exception reach native code.
 *
 * <p>{@code -Dantennapod.desktop.thumbbar=false} leaves the window procedure alone.
 */
final class ThumbBar {
    interface Callbacks {
        void onPrevious();

        void onPlayPause();

        void onNext();
    }

    private static final int WM_COMMAND = 0x0111;
    private static final int THBN_CLICKED = 0x1800;
    private static final int GWLP_WNDPROC = -4;

    /** THUMBBUTTONMASK. */
    private static final int THB_ICON = 0x2;
    private static final int THB_TOOLTIP = 0x4;
    private static final int THB_FLAGS = 0x8;
    /** THUMBBUTTONFLAGS. */
    private static final int THBF_ENABLED = 0x0;

    private static final int ID_PREVIOUS = 101;
    private static final int ID_PLAY_PAUSE = 102;
    private static final int ID_NEXT = 103;
    private static final int BUTTON_COUNT = 3;

    private final HWND hwnd;
    private final WindowsTaskbar.TaskbarList3 taskbarList;
    private final Callbacks callbacks;
    private final int taskbarButtonCreatedMessage;
    /**
     * Runs work on the thread that created the shell object. The window procedure runs on the
     * window's thread, so every COM call it triggers has to be handed back to the apartment that
     * owns the interface rather than made from wherever the message arrived.
     */
    private final java.util.function.Consumer<Runnable> onComThread;

    /**
     * Held for as long as the window lives. Windows keeps a raw pointer to this callback, so
     * letting it be collected would leave the message loop calling into freed memory.
     */
    private WinUser.WindowProc windowProc;
    private LONG_PTR previousWindowProc;

    private THUMBBUTTON[] buttons;
    private HICON previousIcon;
    private HICON playIcon;
    private HICON pauseIcon;
    private HICON nextIcon;
    private boolean added;
    private boolean showingPause;

    private ThumbBar(HWND hwnd, WindowsTaskbar.TaskbarList3 taskbarList, Callbacks callbacks,
            java.util.function.Consumer<Runnable> onComThread) {
        this.hwnd = hwnd;
        this.taskbarList = taskbarList;
        this.callbacks = callbacks;
        this.onComThread = onComThread;
        this.taskbarButtonCreatedMessage =
                Win32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated");
    }

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("antennapod.desktop.thumbbar", "true"));
    }

    /**
     * Subclasses the window and prepares the buttons. Returns null if anything about the native
     * side is not as expected, which leaves the window exactly as it was.
     */
    static ThumbBar install(HWND hwnd, WindowsTaskbar.TaskbarList3 taskbarList,
            Callbacks callbacks, java.util.function.Consumer<Runnable> onComThread) {
        if (!isEnabled() || hwnd == null || taskbarList == null) {
            return null;
        }
        try {
            ThumbBar bar = new ThumbBar(hwnd, taskbarList, callbacks, onComThread);
            bar.buildIcons();
            bar.buildButtons();
            if (!bar.subclassWindow()) {
                bar.destroyIcons();
                return null;
            }
            // the taskbar button usually exists by now, so try immediately as well as on the
            // shell announcement — whichever comes first wins and the other is a no-op
            onComThread.accept(bar::addButtons);
            return bar;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    /** Swaps the middle button between play and pause. */
    void setPlaying(boolean playing) {
        if (!added || playing == showingPause) {
            return;
        }
        showingPause = playing;
        onComThread.accept(() -> {
            try {
                THUMBBUTTON middle = buttons[1];
                middle.hIcon = playing ? pauseIcon : playIcon;
                setTip(middle, playing ? "Pause" : "Play");
                middle.write();
                taskbarList.thumbBarUpdateButtons(hwnd, BUTTON_COUNT, buttons[0].getPointer());
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /** Puts the original window procedure back and frees the icons. */
    void dispose() {
        try {
            if (previousWindowProc != null) {
                Win32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, previousWindowProc);
                previousWindowProc = null;
            }
        } catch (Throwable t) {
            // the window is going away regardless
        }
        windowProc = null;
        destroyIcons();
    }

    // ---------------------------------------------------------------- message loop

    private boolean subclassWindow() {
        windowProc = this::handleMessage;
        try {
            LONG_PTR previous = Win32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, windowProc);
            if (previous == null || previous.longValue() == 0) {
                windowProc = null;
                return false;
            }
            previousWindowProc = previous;
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            windowProc = null;
            return false;
        }
    }

    /**
     * Runs on the window's own thread, inside the message loop. Anything thrown here would cross
     * back into native code, so nothing is allowed out, and every message we do not recognise goes
     * straight on to the procedure we replaced.
     */
    private LRESULT handleMessage(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        try {
            if (message == taskbarButtonCreatedMessage) {
                onComThread.accept(this::addButtons);
            } else if (message == WM_COMMAND && highWord(wParam.intValue()) == THBN_CLICKED) {
                dispatch(lowWord(wParam.intValue()));
                return new LRESULT(0);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return Win32.INSTANCE.CallWindowProc(previousWindowProc, window, message, wParam, lParam);
    }

    private void dispatch(int buttonId) {
        Runnable action;
        switch (buttonId) {
            case ID_PREVIOUS: action = callbacks::onPrevious; break;
            case ID_PLAY_PAUSE: action = callbacks::onPlayPause; break;
            case ID_NEXT: action = callbacks::onNext; break;
            default: return;
        }
        // hand back to the application thread rather than acting inside the message loop
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private static int lowWord(int value) {
        return value & 0xFFFF;
    }

    private static int highWord(int value) {
        return (value >> 16) & 0xFFFF;
    }

    // ---------------------------------------------------------------- buttons

    private void buildButtons() {
        buttons = (THUMBBUTTON[]) new THUMBBUTTON().toArray(BUTTON_COUNT);
        fill(buttons[0], ID_PREVIOUS, previousIcon, "Previous");
        fill(buttons[1], ID_PLAY_PAUSE, playIcon, "Play");
        fill(buttons[2], ID_NEXT, nextIcon, "Next");
    }

    private void fill(THUMBBUTTON button, int id, HICON icon, String tip) {
        button.dwMask = THB_ICON | THB_TOOLTIP | THB_FLAGS;
        button.iId = id;
        button.hIcon = icon;
        button.dwFlags = THBF_ENABLED;
        setTip(button, tip);
        button.write();
    }

    private static void setTip(THUMBBUTTON button, String tip) {
        Arrays.fill(button.szTip, '\0');
        for (int i = 0; i < tip.length() && i < button.szTip.length - 1; i++) {
            button.szTip[i] = tip.charAt(i);
        }
    }

    private synchronized void addButtons() {
        if (added || buttons == null) {
            return;
        }
        if (taskbarList.thumbBarAddButtons(hwnd, BUTTON_COUNT, buttons[0].getPointer())
                .intValue() >= 0) {
            added = true;
        }
    }

    // ---------------------------------------------------------------- icons

    private void buildIcons() {
        int size = Math.max(User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSMICON), 16);
        // the thumbnail toolbar follows the system theme, not the app's
        Color ink = SystemTheme.isDark() ? new Color(0xF0, 0xF0, 0xF0) : new Color(0x20, 0x20, 0x20);
        previousIcon = toIcon(draw(size, ink, Glyph.PREVIOUS));
        playIcon = toIcon(draw(size, ink, Glyph.PLAY));
        pauseIcon = toIcon(draw(size, ink, Glyph.PAUSE));
        nextIcon = toIcon(draw(size, ink, Glyph.NEXT));
    }

    private void destroyIcons() {
        for (HICON icon : new HICON[]{previousIcon, playIcon, pauseIcon, nextIcon}) {
            if (icon != null) {
                try {
                    Win32.INSTANCE.DestroyIcon(icon);
                } catch (Throwable t) {
                    // nothing useful to do while tearing down
                }
            }
        }
        previousIcon = playIcon = pauseIcon = nextIcon = null;
    }

    enum Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

    /** The same shapes as the in-app transport controls, drawn at icon size. */
    static BufferedImage draw(int size, Color ink, Glyph glyph) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ink);
        double pad = size * 0.28;
        double left = pad;
        double right = size - pad;
        double top = pad;
        double bottom = size - pad;
        switch (glyph) {
            case PLAY:
                g.fill(triangle(left, top, left, bottom, right, size / 2.0));
                break;
            case PAUSE: {
                double barWidth = (right - left) * 0.32;
                g.fill(new java.awt.geom.Rectangle2D.Double(left, top, barWidth, bottom - top));
                g.fill(new java.awt.geom.Rectangle2D.Double(right - barWidth, top, barWidth,
                        bottom - top));
                break;
            }
            case PREVIOUS: {
                double barWidth = Math.max(size * 0.09, 1.5);
                g.setStroke(new BasicStroke((float) barWidth, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER));
                g.draw(new java.awt.geom.Line2D.Double(left, top, left, bottom));
                g.fill(triangle(right, top, right, bottom, left + barWidth, size / 2.0));
                break;
            }
            case NEXT: {
                double barWidth = Math.max(size * 0.09, 1.5);
                g.setStroke(new BasicStroke((float) barWidth, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER));
                g.draw(new java.awt.geom.Line2D.Double(right, top, right, bottom));
                g.fill(triangle(left, top, left, bottom, right - barWidth, size / 2.0));
                break;
            }
            default:
                break;
        }
        g.dispose();
        return image;
    }

    private static Path2D triangle(double x1, double y1, double x2, double y2, double x3,
            double y3) {
        Path2D path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.closePath();
        return path;
    }

    /**
     * Turns the drawn image into an HICON: a 32-bit colour bitmap with the pixels written straight
     * into it, plus the all-zero mask that means "take the alpha from the colour bitmap".
     */
    private static HICON toIcon(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
        info.bmiHeader.biSize = info.bmiHeader.size();
        info.bmiHeader.biWidth = width;
        // negative height makes it top-down, matching the order getRGB gives us
        info.bmiHeader.biHeight = -height;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = WinGDI.BI_RGB;

        PointerByReference bits = new PointerByReference();
        HBITMAP colour = GDI32.INSTANCE.CreateDIBSection(new HDC(Pointer.NULL), info,
                WinGDI.DIB_RGB_COLORS, bits, null, 0);
        if (colour == null || bits.getValue() == null) {
            return null;
        }
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        bits.getValue().write(0, pixels, 0, pixels.length);

        HBITMAP mask = Gdi32Ex.INSTANCE.CreateBitmap(width, height, 1, 1, null);
        try {
            WinGDI.ICONINFO iconInfo = new WinGDI.ICONINFO();
            iconInfo.fIcon = true;
            iconInfo.hbmColor = colour;
            iconInfo.hbmMask = mask;
            iconInfo.write();
            return Win32.INSTANCE.CreateIconIndirect(iconInfo);
        } finally {
            GDI32.INSTANCE.DeleteObject(colour);
            if (mask != null) {
                GDI32.INSTANCE.DeleteObject(mask);
            }
        }
    }

    /** THUMBBUTTON, as ITaskbarList3 expects it. */
    public static class THUMBBUTTON extends Structure {
        public int dwMask;
        public int iId;
        public int iBitmap;
        public HICON hIcon;
        public char[] szTip = new char[260];
        public int dwFlags;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags");
        }
    }

    /** The mask bitmap call, which JNA's GDI32 binding does not cover. */
    interface Gdi32Ex extends StdCallLibrary {
        Gdi32Ex INSTANCE = Native.load("gdi32", Gdi32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        HBITMAP CreateBitmap(int width, int height, int planes, int bitCount, Pointer bits);
    }

    /** The handful of user32 calls JNA's own bindings do not cover. */
    interface Win32 extends StdCallLibrary {
        Win32 INSTANCE = Native.load("user32", Win32.class, W32APIOptions.DEFAULT_OPTIONS);

        LONG_PTR SetWindowLongPtr(HWND hWnd, int index, WinUser.WindowProc proc);

        LONG_PTR SetWindowLongPtr(HWND hWnd, int index, LONG_PTR value);

        LRESULT CallWindowProc(LONG_PTR proc, HWND hWnd, int message, WPARAM wParam, LPARAM lParam);

        int RegisterWindowMessage(String name);

        HICON CreateIconIndirect(WinGDI.ICONINFO info);

        boolean DestroyIcon(HICON icon);
    }
}
