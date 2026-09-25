package de.danoeh.antennapod.desktop;

import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary.StdCallCallback;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.stage.Stage;

/**
 * The episode in the Windows volume flyout and quick-settings media card: title, artist, artwork,
 * playback status and position, with working play/pause/stop/previous/next buttons.
 *
 * <p>JavaFX plays the audio but never tells Windows about it, so this talks to the System Media
 * Transport Controls directly. There is no WinRT binding in JNA, so every interface below is
 * called through its vtable like {@link WindowsTaskbar.TaskbarList3}: the slot numbers and
 * interface ids come straight from the Windows SDK headers ({@code windows.media.h},
 * {@code windows.storage.streams.h}, {@code asyncinfo.h} and
 * {@code um/SystemMediaTransportControlsInterop.h}). The desktop path is used on purpose: the
 * controls are fetched for the app window ({@code GetForWindow}), never by activating a media
 * player, because nothing here plays through WinRT.
 *
 * <p>Nothing here throws at the caller. Off Windows, without WinRT, or if the shell refuses, every
 * method quietly does nothing, so callers never have to ask whether it is available. Starting with
 * {@code -Dantennapod.desktop.smtc=false} turns it off.
 */
public final class SmtcManager {
    /** Buttons on the card, forwarded to whoever owns playback. */
    public interface Callbacks {
        void onPlay();

        void onPause();

        void onStop();

        void onNext();

        void onPrevious();
    }

    // ------------------------------------------------------------------ interface ids
    static final String IID_INTEROP =
            "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a";
    private static final String IID_CONTROLS =
            "99fa3ff4-1742-42a6-902e-087d41f965ec";
    static final String IID_CONTROLS_2 =
            "ea98d2f6-7f3c-4af2-a586-72889808efb1";
    private static final String IID_DISPLAY_UPDATER =
            "8abbc53e-fa55-4ecf-ad8e-c984e5dd1550";
    private static final String IID_MUSIC_PROPS =
            "6bbf0c59-d0a0-4d26-92a0-f978e1d18e7b";
    static final String IID_TIMELINE_PROPS =
            "5125316a-c3a2-475b-8507-93534dc88f15";
    private static final String IID_BUTTON_ARGS =
            "b7f47116-a56f-4dc8-9e11-92031f4a87c2";
    static final String IID_STREAM_REF_STATICS =
            "857309dc-3fbf-4e7d-986f-ef3b1a07a964";
    static final String IID_RANDOM_ACCESS_STREAM =
            "905a0fe1-bc53-11df-8c49-001e4fc686da";
    static final String IID_DATA_WRITER_FACTORY =
            "338c67c2-8b84-4c2b-9c50-7b8767847a1f";
    /** TypedEventHandler&lt;SystemMediaTransportControls,
     * SystemMediaTransportControlsButtonPressedEventArgs&gt;. */
    private static final String IID_BUTTON_HANDLER =
            "0557e996-7b23-5bae-aa81-ea0d671143a4";
    static final String IID_ASYNC_INFO =
            "00000036-0000-0000-C000-000000000046";
    private static final String IID_IUNKNOWN =
            "00000000-0000-0000-C000-000000000046";
    private static final String IID_IINSPECTABLE =
            "AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90";

    // ------------------------------------------------------------------ runtime classes
    static final String CLASS_CONTROLS = "Windows.Media.SystemMediaTransportControls";
    static final String CLASS_TIMELINE_PROPS =
            "Windows.Media.SystemMediaTransportControlsTimelineProperties";
    static final String CLASS_STREAM_REF = "Windows.Storage.Streams.RandomAccessStreamReference";
    static final String CLASS_MEMORY_STREAM = "Windows.Storage.Streams.InMemoryRandomAccessStream";
    static final String CLASS_DATA_WRITER = "Windows.Storage.Streams.DataWriter";

    // ------------------------------------------------------------------ enums
    /** MediaPlaybackType. */
    private static final int TYPE_MUSIC = 1;
    /** MediaPlaybackStatus. */
    static final int STATUS_STOPPED = 2;
    static final int STATUS_PLAYING = 3;
    static final int STATUS_PAUSED = 4;
    /** SystemMediaTransportControlsButton, the values the card can send back. */
    static final int BUTTON_PLAY = 0;
    static final int BUTTON_PAUSE = 1;
    static final int BUTTON_STOP = 2;
    static final int BUTTON_NEXT = 6;
    static final int BUTTON_PREVIOUS = 7;
    /** AsyncStatus. */
    private static final int ASYNC_STARTED = 0;
    private static final int ASYNC_COMPLETED = 1;

    private static final int RO_INIT_MULTITHREADED = 1;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int S_OK = 0;
    /** 100ns ticks in a millisecond: the unit of every TimeSpan below. */
    private static final long TICKS_PER_MS = 10_000L;
    /** How long an in-memory store may take before the card goes without artwork. */
    private static final long STORE_WAIT_MS = 2_000L;
    /** Timeline pushes are throttled to this: the shell animates between them anyway. */
    private static final int TIMELINE_PUSH_MS = 2_000;

    /**
     * The WinRT half of this belongs to the apartment that created it, so every call has to come
     * from the same thread. One thread owns it from RoInitialize to RoUninitialize.
     */
    private final ExecutorService comThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "windows-smtc");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Controls controls;
    private volatile Controls2 controls2;
    private volatile Pointer dataWriterFactory;
    private volatile Pointer streamRefStatics;
    private volatile ButtonSink buttonSink;
    private volatile long buttonToken;
    private volatile boolean buttonRegistered;
    private volatile Callbacks callbacks;
    private volatile java.util.function.Consumer<String> errorReporter;
    private volatile String lastReportedError;
    private volatile int lastTimelineMs = -1;
    private volatile int lastDurationMs = -1;
    // set before attach completes, applied once the controls exist
    private volatile PendingEpisode pendingEpisode;
    private volatile int pendingStatus = -1;

    private static final class PendingEpisode {
        final String title;
        final String artist;
        final String album;
        final File artwork;

        PendingEpisode(String title, String artist, String album, File artwork) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
        }
    }

    public static boolean isEnabled() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win")
                && !"false".equalsIgnoreCase(System.getProperty("antennapod.desktop.smtc", "true"));
    }

    /**
     * Where one-line failure notes go (the app shows them in the status log). Failures are also
     * printed to the console; this just makes them visible where the app runs without one.
     * Each distinct message is reported once, so a persistent failure does not flood the log.
     */
    public void setErrorReporter(java.util.function.Consumer<String> reporter) {
        this.errorReporter = reporter;
    }

    private void reportError(String message) {
        try {
            if (message.equals(lastReportedError)) {
                return;
            }
            lastReportedError = message;
            java.util.function.Consumer<String> reporter = errorReporter;
            if (reporter != null) {
                reporter.accept(message);
            }
        } catch (Throwable ignored) {
            // reporting must never break playback
        }
    }

    /**
     * Fetches the transport controls for the window and hooks up the card buttons. Call once the
     * stage is showing: the window has to exist before it can be found.
     */
    public void attach(Stage stage, Callbacks callbacks) {
        if (!isEnabled()) {
            return;
        }
        this.callbacks = callbacks;
        String title = stage.getTitle();
        submit(() -> {
            try {
                HWND window = awaitWindow(title);
                if (window == null) {
                    reportError("app window not found");
                    return;
                }
                int hr = ComBase.INSTANCE.RoInitialize(RO_INIT_MULTITHREADED);
                if (hr < 0) {
                    reportError("WinRT unavailable (" + hr + ")");
                    return;
                }
                Pointer factory = getActivationFactory(CLASS_CONTROLS, IID_INTEROP);
                if (factory == null) {
                    reportError("media controls unavailable");
                    return;
                }
                Pointer controlsPtr;
                try {
                    controlsPtr = getForWindow(factory, window);
                } finally {
                    release(factory);
                }
                if (controlsPtr == null) {
                    reportError("no media controls for this window");
                    return;
                }
                controls = new Controls(controlsPtr);
                Pointer second = queryInterface(controlsPtr, IID_CONTROLS_2);
                if (second != null) {
                    controls2 = new Controls2(second);
                }
                dataWriterFactory = getActivationFactory(CLASS_DATA_WRITER, IID_DATA_WRITER_FACTORY);
                streamRefStatics = getActivationFactory(CLASS_STREAM_REF, IID_STREAM_REF_STATICS);
                controls.putIsPlayEnabled(true);
                controls.putIsPauseEnabled(true);
                controls.putIsStopEnabled(true);
                controls.putIsPreviousEnabled(true);
                controls.putIsNextEnabled(true);
                buttonSink = new ButtonSink();
                buttonToken = controls.addButtonPressed(buttonSink.pointer());
                buttonRegistered = buttonToken != 0;
                controls.putIsEnabled(true);
                PendingEpisode pending = pendingEpisode;
                pendingEpisode = null;
                if (pending != null) {
                    pushEpisode(pending.title, pending.artist, pending.album, pending.artwork);
                }
                int status = pendingStatus;
                pendingStatus = -1;
                if (status >= 0) {
                    pushStatus(status);
                }
            } catch (Throwable t) {
                // an unavailable shell interface is not worth breaking playback over
                reportError("attach failed: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    /**
     * Shows the episode on the card. Artwork is a local image file (or null for none): the shell
     * only reads stream references, so a remote URL cannot be handed over directly.
     */
    public void setEpisode(String title, String artist, String album, File artwork) {
        if (!isEnabled()) {
            return;
        }
        submit(() -> {
            try {
                if (controls == null) {
                    pendingEpisode = new PendingEpisode(title, artist, album, artwork);
                    return;
                }
                pushEpisode(title, artist, album, artwork);
            } catch (Throwable t) {
                reportError("episode update failed: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    /** Playing, paused, or nothing loaded (which hides the card). */
    public void setStatus(boolean loaded, boolean playing) {
        if (!isEnabled()) {
            return;
        }
        int status = !loaded ? STATUS_STOPPED : playing ? STATUS_PLAYING : STATUS_PAUSED;
        submit(() -> {
            try {
                if (controls == null) {
                    pendingStatus = status;
                    return;
                }
                pushStatus(status);
            } catch (Throwable t) {
                reportError("status update failed: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    /** Moves the card's timeline. Throttled: per-second ticks would just be COM chatter. */
    public void setTimeline(int positionMs, int durationMs) {
        if (!isEnabled() || durationMs <= 0 || positionMs < 0) {
            return;
        }
        int lastPos = lastTimelineMs;
        if (durationMs == lastDurationMs && lastPos >= 0 && Math.abs(positionMs - lastPos) < TIMELINE_PUSH_MS) {
            return;
        }
        lastTimelineMs = positionMs;
        lastDurationMs = durationMs;
        submit(() -> {
            try {
                if (controls2 == null) {
                    return;
                }
                Pointer props = activate(CLASS_TIMELINE_PROPS);
                if (props == null) {
                    return;
                }
                try {
                    Pointer timeline = queryInterface(props, IID_TIMELINE_PROPS);
                    if (timeline == null) {
                        return;
                    }
                    try {
                        TimelineProps timelineProps = new TimelineProps(timeline);
                        timelineProps.putStartTime(0);
                        timelineProps.putEndTime(durationMs * TICKS_PER_MS);
                        timelineProps.putMinSeekTime(0);
                        timelineProps.putMaxSeekTime(durationMs * TICKS_PER_MS);
                        timelineProps.putPosition(Math.min(positionMs, durationMs) * TICKS_PER_MS);
                        controls2.updateTimelineProperties(timelineProps.getPointer());
                    } finally {
                        release(timeline);
                    }
                } finally {
                    release(props);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    public void shutdown() {
        submit(() -> {
            try {
                if (controls != null && buttonRegistered && buttonSink != null) {
                    try {
                        controls.removeButtonPressed(buttonToken);
                    } catch (Throwable ignored) {
                        // shutting down anyway
                    }
                    buttonRegistered = false;
                }
                buttonSink = null;
                if (controls != null) {
                    try {
                        controls.putIsEnabled(false);
                    } catch (Throwable ignored) {
                        // shutting down anyway
                    }
                }
                if (controls2 != null) {
                    releaseQuietly(controls2.getPointer());
                    controls2 = null;
                }
                if (controls != null) {
                    releaseQuietly(controls.getPointer());
                    controls = null;
                }
                if (streamRefStatics != null) {
                    releaseQuietly(streamRefStatics);
                    streamRefStatics = null;
                }
                if (dataWriterFactory != null) {
                    releaseQuietly(dataWriterFactory);
                    dataWriterFactory = null;
                }
                lastTimelineMs = -1;
                lastDurationMs = -1;
                try {
                    ComBase.INSTANCE.RoUninitialize();
                } catch (Throwable ignored) {
                    // shutting down anyway
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
        comThread.shutdown();
        try {
            comThread.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A card button, on the system's own thread: route it out, never work here. */
    private void onSystemButton(int button) {
        Callbacks target = callbacks;
        if (target == null) {
            return;
        }
        try {
            switch (button) {
                case BUTTON_PLAY -> target.onPlay();
                case BUTTON_PAUSE -> target.onPause();
                case BUTTON_STOP -> target.onStop();
                case BUTTON_NEXT -> target.onNext();
                case BUTTON_PREVIOUS -> target.onPrevious();
                default -> {
                    // fast-forward, rewind and channels are not enabled, so never arrive
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void pushStatus(int status) {
        controls.putIsEnabled(status != STATUS_STOPPED);
        controls.putPlaybackStatus(status);
    }

    private void pushEpisode(String title, String artist, String album, File artwork) {
        Pointer updaterPtr = controls.getDisplayUpdater();
        if (updaterPtr == null) {
            return;
        }
        try {
            DisplayUpdater updater = new DisplayUpdater(updaterPtr);
            updater.clearAll();
            updater.putType(TYPE_MUSIC);
            Pointer musicPtr = updater.getMusicProperties();
            if (musicPtr != null) {
                try {
                    MusicProps music = new MusicProps(musicPtr);
                    music.putTitle(title);
                    music.putArtist(artist);
                    music.putAlbumTitle(album);
                } finally {
                    release(musicPtr);
                }
            }
            Pointer thumbnail = thumbnailFor(artwork);
            if (thumbnail != null) {
                try {
                    updater.putThumbnail(thumbnail);
                } finally {
                    release(thumbnail);
                }
            }
            updater.update();
        } finally {
            release(updaterPtr);
        }
    }

    /**
     * The artwork as a stream reference the shell can read, or null to leave it out. The bytes
     * go into an in-memory stream rather than through a StorageFile: the broker lookup behind
     * a file path never completes on a thread without a message pump, while the memory stream
     * stays entirely in this process.
     */
    private Pointer thumbnailFor(File artwork) {
        if (artwork == null || !artwork.isFile()
                || dataWriterFactory == null || streamRefStatics == null) {
            return null;
        }
        byte[] image;
        try {
            image = java.nio.file.Files.readAllBytes(artwork.toPath());
        } catch (Exception e) {
            return null;
        }
        if (image.length == 0 || image.length > SmtcArtwork.MAX_BYTES) {
            return null;
        }
        Pointer stream = activate(CLASS_MEMORY_STREAM);
        if (stream == null) {
            return null;
        }
        try {
            Pointer random = queryInterface(stream, IID_RANDOM_ACCESS_STREAM);
            if (random == null) {
                return null;
            }
            try {
                RandomAccessStream randomStream = new RandomAccessStream(random);
                Pointer output = randomStream.getOutputStreamAt(0);
                if (output == null) {
                    return null;
                }
                try {
                    Pointer writer = new DataWriterFactory(dataWriterFactory).createDataWriter(output);
                    if (writer == null) {
                        return null;
                    }
                    try {
                        DataWriter dataWriter = new DataWriter(writer);
                        dataWriter.writeBytes(image);
                        if (!storeAndDetach(dataWriter)) {
                            return null;
                        }
                    } finally {
                        release(writer);
                    }
                    randomStream.seek(0);
                    return new StreamRefStatics(streamRefStatics).createFromStream(stream);
                } finally {
                    release(output);
                }
            } finally {
                release(random);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            release(stream);
        }
    }

    /** Flushes the written bytes into the memory stream and hands back its output. */
    private static boolean storeAndDetach(DataWriter dataWriter) {
        Pointer operation;
        try {
            operation = dataWriter.storeAsync();
        } catch (Throwable t) {
            return false;
        }
        if (operation == null) {
            return false;
        }
        try {
            AsyncUint async = new AsyncUint(operation);
            long waited = 0;
            while (waited < STORE_WAIT_MS) {
                int status;
                try {
                    status = async.getStatus();
                } catch (Throwable t) {
                    return false;
                }
                if (status == ASYNC_COMPLETED) {
                    break;
                }
                if (status != ASYNC_STARTED) {
                    return false;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                waited += 10;
            }
            try {
                async.getResults();
            } catch (Throwable t) {
                return false;
            }
            try {
                Pointer detached = dataWriter.detachStream();
                if (detached != null) {
                    release(detached);
                }
            } catch (Throwable t) {
                return false;
            }
            return true;
        } finally {
            release(operation);
        }
    }

    // ------------------------------------------------------------------ com plumbing

    private void submit(Runnable action) {
        try {
            comThread.submit(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // already shut down
        }
    }

    /** Our own top-level window, found the same way the taskbar integration finds it. */
    private static HWND awaitWindow(String title) {
        for (int attempt = 0; attempt < 40; attempt++) {
            HWND window = WindowsTaskbar.findWindow(title);
            if (window != null) {
                return window;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static Guid.REFIID refiid(String guid) {
        return new Guid.REFIID(new Guid.IID(guid));
    }

    static Pointer getActivationFactory(String className, String iid) {
        Pointer hstring = null;
        try {
            hstring = WinRt.createHString(className);
            PointerByReference out = new PointerByReference();
            int hr = ComBase.INSTANCE.RoGetActivationFactory(hstring, refiid(iid), out);
            if (hr < 0 || out.getValue() == null) {
                return null;
            }
            return out.getValue();
        } catch (Throwable t) {
            return null;
        } finally {
            WinRt.deleteHString(hstring);
        }
    }

    static Pointer activate(String className) {
        Pointer hstring = null;
        try {
            hstring = WinRt.createHString(className);
            PointerByReference out = new PointerByReference();
            int hr = ComBase.INSTANCE.RoActivateInstance(hstring, out);
            if (hr < 0 || out.getValue() == null) {
                return null;
            }
            return out.getValue();
        } catch (Throwable t) {
            return null;
        } finally {
            WinRt.deleteHString(hstring);
        }
    }

    static Pointer getForWindow(Pointer factory, HWND window) {
        try {
            PointerByReference out = new PointerByReference();
            int hr = new Interop(factory).getForWindow(window, out);
            if (hr < 0 || out.getValue() == null) {
                return null;
            }
            return out.getValue();
        } catch (Throwable t) {
            return null;
        }
    }

    static Pointer queryInterface(Pointer instance, String iid) {
        try {
            PointerByReference out = new PointerByReference();
            HRESULT hr = new Unknown(instance).QueryInterface(refiid(iid), out);
            if (hr.intValue() < 0 || out.getValue() == null) {
                return null;
            }
            return out.getValue();
        } catch (Throwable t) {
            return null;
        }
    }

    static void release(Pointer instance) {
        new Unknown(instance).Release();
    }

    private static void releaseQuietly(Pointer instance) {
        try {
            release(instance);
        } catch (Throwable ignored) {
            // shutting down anyway
        }
    }

    // ------------------------------------------------------------------ interfaces

    /** combase.dll: the WinRT bootstrap JNA ships no binding for. */
    interface ComBase extends StdCallLibrary {
        ComBase INSTANCE = Native.load("combase", ComBase.class, W32APIOptions.DEFAULT_OPTIONS);

        int RoInitialize(int initType);

        void RoUninitialize();

        int RoActivateInstance(Pointer classId, PointerByReference instance);

        int RoGetActivationFactory(Pointer classId, Guid.REFIID iid, PointerByReference factory);

        int WindowsCreateString(Pointer sourceString, int length, PointerByReference hstring);

        int WindowsDeleteString(Pointer hstring);

        Pointer WindowsGetStringRawBuffer(Pointer hstring, IntByReference length);
    }

    /** HSTRING lifetime, kept in one place because every string crosses it twice. */
    static final class WinRt {
        private WinRt() {
        }

        static Pointer createHString(String text) {
            if (text == null || text.isEmpty()) {
                // an empty WinRT string is a null handle by design: the API reports
                // success yet hands back NULL, which the old null check mistook for a
                // failure ("WindowsCreateString failed: 0") on episodes with no
                // artist or album. Skip the call; null is already the empty string.
                return null;
            }
            byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
            Memory buffer = new Memory(utf16.length + 2);
            buffer.write(0, utf16, 0, utf16.length);
            buffer.setShort(utf16.length, (short) 0);
            PointerByReference out = new PointerByReference();
            int hr = ComBase.INSTANCE.WindowsCreateString(buffer, text.length(), out);
            if (hr < 0 || out.getValue() == null) {
                throw new RuntimeException("WindowsCreateString failed: " + hr);
            }
            return out.getValue();
        }

        static void deleteHString(Pointer hstring) {
            if (hstring != null) {
                ComBase.INSTANCE.WindowsDeleteString(hstring);
            }
        }

        static String readHString(Pointer hstring) {
            if (hstring == null) {
                return "";
            }
            IntByReference length = new IntByReference();
            Pointer buffer = ComBase.INSTANCE.WindowsGetStringRawBuffer(hstring, length);
            int chars = length.getValue();
            if (buffer == null || chars <= 0) {
                return "";
            }
            return new String(buffer.getByteArray(0, chars * 2), StandardCharsets.UTF_16LE);
        }

        /** Puts a Java string through a WinRT string property: create, set, delete. */
        static void putString(WinRtObject target, int vtbl, String text) {
            Pointer hstring = null;
            try {
                hstring = createHString(text == null ? "" : text);
                target.call(vtbl, hstring);
            } finally {
                deleteHString(hstring);
            }
        }

        /** Reads a WinRT string property, deleting the returned string. */
        static String getString(WinRtObject target, int vtbl) {
            Pointer hstring = target.callOut(vtbl);
            if (hstring == null) {
                return "";
            }
            try {
                return readHString(hstring);
            } finally {
                deleteHString(hstring);
            }
        }
    }

    /**
     * {@link Unknown} exposes its vtable calls as protected, so the small helpers every interface
     * needs live here: {@code call} for HRESULT methods, {@code callOut} for ones handing back a
     * pointer, {@code callInt} for the rest. Failures throw, and callers decide whether that is
     * worth reporting: most of the time it is not.
     */
    static class WinRtObject extends Unknown {
        WinRtObject(Pointer instance) {
            super(instance);
        }

        int call(int vtbl, Object... args) {
            Object[] full = new Object[args.length + 1];
            full[0] = getPointer();
            System.arraycopy(args, 0, full, 1, args.length);
            HRESULT hr = (HRESULT) _invokeNativeObject(vtbl, full, HRESULT.class);
            if (hr.intValue() < 0) {
                throw new RuntimeException("WinRT call failed at slot " + vtbl + ": " + hr.intValue());
            }
            return hr.intValue();
        }

        int callInt(int vtbl, Object... args) {
            Object[] full = new Object[args.length + 1];
            full[0] = getPointer();
            System.arraycopy(args, 0, full, 1, args.length);
            return _invokeNativeInt(vtbl, full);
        }

        /**
         * Reads an integer property: every WinRT method returns an HRESULT, so the value
         * arrives through an out-param, never as the return.
         */
        int getInt(int vtbl) {
            IntByReference out = new IntByReference();
            Object[] full = new Object[]{getPointer(), out};
            HRESULT hr = (HRESULT) _invokeNativeObject(vtbl, full, HRESULT.class);
            if (hr.intValue() < 0) {
                throw new RuntimeException("WinRT call failed at slot " + vtbl + ": " + hr.intValue());
            }
            return out.getValue();
        }

        Pointer callOut(int vtbl, Object... args) {
            PointerByReference out = new PointerByReference();
            Object[] full = new Object[args.length + 2];
            full[0] = getPointer();
            System.arraycopy(args, 0, full, 1, args.length);
            full[full.length - 1] = out;
            HRESULT hr = (HRESULT) _invokeNativeObject(vtbl, full, HRESULT.class);
            if (hr.intValue() < 0 || out.getValue() == null) {
                throw new RuntimeException("WinRT call failed at slot " + vtbl + ": " + hr.intValue());
            }
            return out.getValue();
        }

        void putBoolean(int vtbl, boolean value) {
            call(vtbl, value ? (byte) 1 : (byte) 0);
        }

        boolean getBoolean(int vtbl) {
            ByteByReference out = new ByteByReference();
            Object[] full = new Object[]{getPointer(), out};
            HRESULT hr = (HRESULT) _invokeNativeObject(vtbl, full, HRESULT.class);
            if (hr.intValue() < 0) {
                throw new RuntimeException("WinRT call failed at slot " + vtbl + ": " + hr.intValue());
            }
            return out.getValue() != 0;
        }
    }

    static final class Interop extends WinRtObject {
        Interop(Pointer instance) {
            super(instance);
        }

        int getForWindow(HWND window, PointerByReference out) {
            Object[] full = new Object[]{getPointer(), window, refiid(IID_CONTROLS), out};
            HRESULT hr = (HRESULT) _invokeNativeObject(6, full, HRESULT.class);
            return hr.intValue();
        }
    }

    static final class Controls extends WinRtObject {
        Controls(Pointer instance) {
            super(instance);
        }

        int putPlaybackStatus(int status) {
            return call(7, status);
        }

        int getPlaybackStatus() {
            return getInt(6);
        }

        Pointer getDisplayUpdater() {
            return callOut(8);
        }

        void putIsEnabled(boolean value) {
            putBoolean(11, value);
        }

        boolean getIsEnabled() {
            return getBoolean(10);
        }

        void putIsPlayEnabled(boolean value) {
            putBoolean(13, value);
        }

        void putIsStopEnabled(boolean value) {
            putBoolean(15, value);
        }

        void putIsPauseEnabled(boolean value) {
            putBoolean(17, value);
        }

        void putIsPreviousEnabled(boolean value) {
            putBoolean(25, value);
        }

        void putIsNextEnabled(boolean value) {
            putBoolean(27, value);
        }

        long addButtonPressed(Pointer handler) {
            Memory token = new Memory(8);
            Object[] full = new Object[]{getPointer(), handler, token};
            HRESULT hr = (HRESULT) _invokeNativeObject(32, full, HRESULT.class);
            if (hr.intValue() < 0) {
                throw new RuntimeException("add_ButtonPressed failed: " + hr.intValue());
            }
            return token.getLong(0);
        }

        void removeButtonPressed(long token) {
            call(33, token);
        }
    }

    static final class Controls2 extends WinRtObject {
        Controls2(Pointer instance) {
            super(instance);
        }

        void updateTimelineProperties(Pointer timeline) {
            call(12, timeline);
        }
    }

    static final class DisplayUpdater extends WinRtObject {
        DisplayUpdater(Pointer instance) {
            super(instance);
        }

        void putType(int type) {
            call(7, type);
        }

        void putThumbnail(Pointer streamRef) {
            call(11, streamRef);
        }

        Pointer getMusicProperties() {
            return callOut(12);
        }

        void clearAll() {
            call(16);
        }

        void update() {
            call(17);
        }
    }

    static final class MusicProps extends WinRtObject {
        MusicProps(Pointer instance) {
            super(instance);
        }

        void putTitle(String text) {
            WinRt.putString(this, 7, text);
        }

        void putArtist(String text) {
            WinRt.putString(this, 11, text);
        }

        void putAlbumTitle(String text) {
            WinRt.putString(this, 9, text);
        }

        String getTitle() {
            return WinRt.getString(this, 6);
        }

        String getArtist() {
            return WinRt.getString(this, 10);
        }
    }

    static final class TimelineProps extends WinRtObject {
        TimelineProps(Pointer instance) {
            super(instance);
        }

        void putStartTime(long ticks) {
            call(7, ticks);
        }

        void putEndTime(long ticks) {
            call(9, ticks);
        }

        void putMinSeekTime(long ticks) {
            call(11, ticks);
        }

        void putMaxSeekTime(long ticks) {
            call(13, ticks);
        }

        void putPosition(long ticks) {
            call(15, ticks);
        }

        long getPosition() {
            // TimeSpan out: 8 bytes through a holder the call writes into
            Memory out = new Memory(8);
            Object[] full = new Object[]{getPointer(), out};
            HRESULT hr = (HRESULT) _invokeNativeObject(14, full, HRESULT.class);
            if (hr.intValue() < 0) {
                throw new RuntimeException("get_Position failed: " + hr.intValue());
            }
            return out.getLong(0);
        }
    }

    static final class RandomAccessStream extends WinRtObject {
        RandomAccessStream(Pointer instance) {
            super(instance);
        }

        Pointer getOutputStreamAt(long position) {
            return callOut(9, position);
        }

        void seek(long position) {
            call(11, position);
        }
    }

    static final class DataWriterFactory extends WinRtObject {
        DataWriterFactory(Pointer instance) {
            super(instance);
        }

        Pointer createDataWriter(Pointer outputStream) {
            return callOut(6, outputStream);
        }
    }

    static final class DataWriter extends WinRtObject {
        DataWriter(Pointer instance) {
            super(instance);
        }

        void writeBytes(byte[] value) {
            call(12, value.length, value);
        }

        Pointer storeAsync() {
            return callOut(29);
        }

        Pointer detachStream() {
            return callOut(32);
        }
    }

    static final class StreamRefStatics extends WinRtObject {
        StreamRefStatics(Pointer instance) {
            super(instance);
        }

        Pointer createFromStream(Pointer stream) {
            return callOut(8, stream);
        }
    }

    static final class AsyncInfo extends WinRtObject {
        AsyncInfo(Pointer instance) {
            super(instance);
        }

        int getStatus() {
            return getInt(7);
        }
    }

    /**
     * An {@code IAsyncOperation} result. Note the vtable is flat: unlike the documentation
     * hierarchy suggests, the parameterized interface does not carry the {@code IAsyncInfo}
     * methods, so status is read through a separate {@link AsyncInfo} queried off the
     * operation (slots 6/7/8 here are put_Completed, get_Completed and GetResults).
     */
    static final class AsyncUint extends WinRtObject {
        AsyncUint(Pointer instance) {
            super(instance);
        }

        int getStatus() {
            Pointer info = queryInterface(getPointer(), IID_ASYNC_INFO);
            if (info == null) {
                throw new RuntimeException("IAsyncInfo not available");
            }
            try {
                return new AsyncInfo(info).getStatus();
            } finally {
                release(info);
            }
        }

        int getResults() {
            IntByReference out = new IntByReference();
            Object[] full = new Object[]{getPointer(), out};
            HRESULT hr = (HRESULT) _invokeNativeObject(8, full, HRESULT.class);
            if (hr.intValue() < 0) {
                throw new RuntimeException("GetResults failed: " + hr.intValue());
            }
            return out.getValue();
        }
    }

    // ------------------------------------------------------------------ button sink

    /**
     * The ButtonPressed handler as native code sees it: one COM object with a four-slot vtable.
     * Every slot is a JNA callback, and every referenced object is held here so nothing is
     * collected while the system may still call it.
     */
    final class ButtonSink {
        interface QueryFn extends StdCallCallback {
            int apply(Pointer self, Pointer riid, PointerByReference ppv);
        }

        interface RefFn extends StdCallCallback {
            int apply(Pointer self);
        }

        interface InvokeFn extends StdCallCallback {
            int apply(Pointer self, Pointer sender, Pointer args);
        }

        private final QueryFn queryFn = this::queryInterface;
        private final RefFn addRefFn = this::addRef;
        private final RefFn releaseFn = this::release;
        private final InvokeFn invokeFn = this::invoke;
        private final AtomicInteger refs = new AtomicInteger(1);
        private final Memory vtable = new Memory(4L * Native.POINTER_SIZE);
        private final Memory self = new Memory(Native.POINTER_SIZE);
        private final byte[] unknownId = guidBytes(IID_IUNKNOWN);
        private final byte[] inspectableId = guidBytes(IID_IINSPECTABLE);
        private final byte[] handlerId = guidBytes(IID_BUTTON_HANDLER);

        ButtonSink() {
            vtable.setPointer(0, CallbackReference.getFunctionPointer(queryFn));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRefFn));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(releaseFn));
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(invokeFn));
            self.setPointer(0, vtable);
        }

        Pointer pointer() {
            return self;
        }

        private int queryInterface(Pointer self, Pointer riid, PointerByReference ppv) {
            try {
                byte[] id = riid.getByteArray(0, 16);
                if (matches(id, unknownId) || matches(id, inspectableId) || matches(id, handlerId)) {
                    refs.incrementAndGet();
                    ppv.setValue(self);
                    return S_OK;
                }
                ppv.setValue(null);
                return E_NOINTERFACE;
            } catch (Throwable t) {
                return E_NOINTERFACE;
            }
        }

        private int addRef(Pointer self) {
            return refs.incrementAndGet();
        }

        private int release(Pointer self) {
            return refs.decrementAndGet();
        }

        private int invoke(Pointer self, Pointer sender, Pointer args) {
            try {
                if (args == null) {
                    return S_OK;
                }
                IntByReference button = new IntByReference();
                // callInt returns the raw HRESULT here; the button arrives in the out-param
                if (new WinRtObject(args).callInt(6, button) < 0) {
                    return S_OK;
                }
                onSystemButton(button.getValue());
                return S_OK;
            } catch (Throwable t) {
                t.printStackTrace();
                return S_OK;
            }
        }
    }

    private static boolean matches(byte[] got, byte[] want) {
        if (got == null || got.length != 16) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (got[i] != want[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] guidBytes(String guid) {
        Guid.IID id = new Guid.IID(guid);
        id.write();
        return id.getPointer().getByteArray(0, 16);
    }
}
