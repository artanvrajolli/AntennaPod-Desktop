package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The Windows media card behind the volume flyout. The pure pieces run everywhere; anything
 * touching WinRT is gated on Windows, and the shell round-trip additionally skips when the
 * session has no shell to talk to — the manager itself degrades the same way.
 */
public class SmtcManagerTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private String originalSmtcProperty;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win");
    }

    @Before
    public void setUp() throws Exception {
        originalSmtcProperty = System.getProperty("antennapod.desktop.smtc");
        byte[] png = artworkPng();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/art.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(png);
            }
        });
        server.createContext("/missing.png", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
        if (originalSmtcProperty == null) {
            System.clearProperty("antennapod.desktop.smtc");
        } else {
            System.setProperty("antennapod.desktop.smtc", originalSmtcProperty);
        }
    }

    /** One black pixel: enough for the shell to accept as artwork. */
    static byte[] artworkPng() {
        return new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
            0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89, 0x00, 0x00, 0x00, 0x0A,
            0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    }

    @Test
    public void testDisabledByProperty() {
        System.setProperty("antennapod.desktop.smtc", "false");
        assertFalse(SmtcManager.isEnabled());
    }

    @Test
    public void testArtworkSuffixes() {
        assertEquals(".png", SmtcArtwork.extensionOf("https://cdn.example/a.png"));
        assertEquals(".jpg", SmtcArtwork.extensionOf("https://cdn.example/a.jpeg?size=300"));
        assertEquals(".jpg", SmtcArtwork.extensionOf("https://cdn.example/a.JPG"));
        assertEquals(".webp", SmtcArtwork.extensionOf("https://cdn.example/a.webp"));
        assertEquals(".img", SmtcArtwork.extensionOf("https://cdn.example/artwork"));
    }

    @Test
    public void testArtworkDownloadsAndIsReused() throws Exception {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        File dir = new File(tempFolder.getRoot(), "smtc-art");
        File first = SmtcArtwork.fetch(base + "/art.png?size=300", dir, 42);
        assertNotNull(first);
        assertTrue(first.isFile());
        assertArrayEquals(artworkPng(), Files.readAllBytes(first.toPath()));
        // a second episode evicts the first; a repeat run reuses what is there
        File second = SmtcArtwork.fetch(base + "/art.png", dir, 43);
        assertNotNull(second);
        assertFalse(first.exists());
        assertEquals(second, SmtcArtwork.fetch(base + "/missing.png", dir, 43));
    }

    @Test
    public void testArtworkMissingIsNull() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        assertNull(SmtcArtwork.fetch(base + "/missing.png", new File(tempFolder.getRoot(), "a"), 1));
        assertNull(SmtcArtwork.fetch("", new File(tempFolder.getRoot(), "b"), 1));
        assertNull(SmtcArtwork.fetch(null, new File(tempFolder.getRoot(), "c"), 1));
    }

    @Test
    public void testTicksToMs() {
        assertEquals(0, SmtcManager.ticksToMs(0));
        assertEquals(0, SmtcManager.ticksToMs(-5));
        assertEquals(120_000, SmtcManager.ticksToMs(120_000L * 10_000L));
        assertEquals(1, SmtcManager.ticksToMs(15_000L));
        assertEquals(Integer.MAX_VALUE, SmtcManager.ticksToMs(Long.MAX_VALUE));
    }

    @Test
    public void testEmptyHStringIsNull() {
        // an empty WinRT string is a null handle by design, so no API call happens:
        // this runs everywhere, unlike the round-trip below
        assertNull(SmtcManager.WinRt.createHString(""));
        assertNull(SmtcManager.WinRt.createHString(null));
    }

    @Test
    public void testHStringRoundTrip() {
        Assume.assumeTrue(isWindows());
        int hr = SmtcManager.ComBase.INSTANCE.RoInitialize(1);
        assertTrue(hr >= 0);
        try {
            String text = "Typical Story II — Glum Aleks ✓";
            Pointer hstring = SmtcManager.WinRt.createHString(text);
            try {
                assertEquals(text, SmtcManager.WinRt.readHString(hstring));
            } finally {
                SmtcManager.WinRt.deleteHString(hstring);
            }
            assertEquals("", SmtcManager.WinRt.readHString(null));
        } finally {
            SmtcManager.ComBase.INSTANCE.RoUninitialize();
        }
    }

    @Test
    public void testButtonSinkAnswersItsOwnInterfaces() {
        Assume.assumeTrue(isWindows());
        SmtcManager manager = new SmtcManager();
        SmtcManager.ButtonSink sink = manager.new ButtonSink();
        try {
            Pointer self = sink.pointer();
            assertNotNull(self);
            Pointer vtable = self.getPointer(0);
            // QueryInterface for the handler iid succeeds and returns the object itself
            Memory iid = new Memory(16);
            iid.write(0, handlerIdBytes(), 0, 16);
            Memory ppv = new Memory(Native.POINTER_SIZE);
            Function query = Function.getFunction(vtable.getPointer(0));
            int hr = query.invokeInt(new Object[]{self, iid, ppv});
            assertEquals(0, hr);
            assertEquals(self, ppv.getPointer(0));
            // an unknown interface is refused
            iid.write(0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 0, 16);
            hr = query.invokeInt(new Object[]{self, iid, ppv});
            assertTrue(hr < 0);
            assertNull(ppv.getPointer(0));
            // Invoke with no args takes the null guard and reports success
            Function invoke = Function.getFunction(vtable.getPointer(3L * Native.POINTER_SIZE));
            assertEquals(0, invoke.invokeInt(new Object[]{self, null, null}));
        } finally {
            manager.shutdown();
        }
    }

    private static byte[] handlerIdBytes() {
        com.sun.jna.platform.win32.Guid.IID id =
                new com.sun.jna.platform.win32.Guid.IID("0557e996-7b23-5bae-aa81-ea0d671143a4");
        id.write();
        return id.getPointer().getByteArray(0, 16);
    }

    @Test
    public void testSessionRoundTripAgainstTheShell() throws Exception {
        Assume.assumeTrue(isWindows());
        int hr = SmtcManager.ComBase.INSTANCE.RoInitialize(1);
        assertTrue(hr >= 0);
        HWND hwnd = null;
        try {
            hwnd = User32.INSTANCE.CreateWindowEx(0, "STATIC", "smtc-test", WinUser.WS_POPUP,
                    0, 0, 100, 100, null, null, null, null);
            Assume.assumeNotNull(hwnd);
            Pointer factory = SmtcManager.getActivationFactory(
                    SmtcManager.CLASS_CONTROLS, SmtcManager.IID_INTEROP);
            Assume.assumeNotNull(factory);
            try {
                Pointer controlsPtr = SmtcManager.getForWindow(factory, hwnd);
                Assume.assumeNotNull(controlsPtr);
                try {
                    verifySession(controlsPtr);
                } finally {
                    new SmtcManager.Controls(controlsPtr).putIsEnabled(false);
                    SmtcManager.release(controlsPtr);
                }
            } finally {
                SmtcManager.release(factory);
            }
        } finally {
            if (hwnd != null) {
                User32.INSTANCE.DestroyWindow(hwnd);
            }
            SmtcManager.ComBase.INSTANCE.RoUninitialize();
        }
    }

    /** Metadata, status, timeline, artwork and the button handler, round-tripped. */
    private void verifySession(Pointer controlsPtr) throws Exception {
        SmtcManager.Controls controls = new SmtcManager.Controls(controlsPtr);
        controls.putIsPlayEnabled(true);
        controls.putIsPauseEnabled(true);
        controls.putIsStopEnabled(true);
        controls.putIsPreviousEnabled(true);
        controls.putIsNextEnabled(true);

        // the button sink the card will call: accepted, then unregistered
        SmtcManager manager = new SmtcManager();
        SmtcManager.ButtonSink sink = manager.new ButtonSink();
        long token = controls.addButtonPressed(sink.pointer());
        controls.removeButtonPressed(token);
        manager.shutdown();

        controls.putIsEnabled(true);
        assertTrue(controls.getIsEnabled());
        controls.putPlaybackStatus(SmtcManager.STATUS_PLAYING);
        assertEquals(SmtcManager.STATUS_PLAYING, controls.getPlaybackStatus());

        Pointer updaterPtr = controls.getDisplayUpdater();
        assertNotNull(updaterPtr);
        try {
            SmtcManager.DisplayUpdater updater = new SmtcManager.DisplayUpdater(updaterPtr);
            updater.clearAll();
            updater.putType(1);
            Pointer musicPtr = updater.getMusicProperties();
            assertNotNull(musicPtr);
            try {
                SmtcManager.MusicProps music = new SmtcManager.MusicProps(musicPtr);
                music.putTitle("Typical Story II");
                music.putArtist("Glum Aleks");
                music.putAlbumTitle("AntennaPod Desktop");
                assertEquals("Typical Story II", music.getTitle());
                assertEquals("Glum Aleks", music.getArtist());
            } finally {
                SmtcManager.release(musicPtr);
            }
            // artwork through a memory stream, the way the app hands it over
            Pointer streamRef = thumbnailViaMemory(artworkPng());
            assertNotNull(streamRef);
            try {
                updater.putThumbnail(streamRef);
            } finally {
                SmtcManager.release(streamRef);
            }
            updater.update();
        } finally {
            SmtcManager.release(updaterPtr);
        }

        Pointer second = SmtcManager.queryInterface(controlsPtr, SmtcManager.IID_CONTROLS_2);
        assertNotNull(second);
        try {
            SmtcManager.Controls2 controls2 = new SmtcManager.Controls2(second);
            Pointer props = SmtcManager.activate(SmtcManager.CLASS_TIMELINE_PROPS);
            assertNotNull(props);
            try {
                Pointer timeline = SmtcManager.queryInterface(props, SmtcManager.IID_TIMELINE_PROPS);
                assertNotNull(timeline);
                try {
                    SmtcManager.TimelineProps timelineProps = new SmtcManager.TimelineProps(timeline);
                    timelineProps.putStartTime(0);
                    timelineProps.putEndTime(3_600_000L * 10_000L);
                    timelineProps.putMinSeekTime(0);
                    timelineProps.putMaxSeekTime(3_600_000L * 10_000L);
                    timelineProps.putPosition(120_000L * 10_000L);
                    controls2.updateTimelineProperties(timelineProps.getPointer());
                    assertEquals(120_000L * 10_000L, timelineProps.getPosition());
                    // the seek handler the card drags against: accepted, then unregistered.
                    // A session without app identity refuses it with E_NOTIMPL; the
                    // timeline still shows there, it just cannot be dragged.
                    SmtcManager seekOwner = new SmtcManager();
                    try {
                        SmtcManager.SeekSink seekSink = seekOwner.new SeekSink();
                        long seekToken;
                        try {
                            seekToken = controls2
                                    .addPlaybackPositionChangeRequested(seekSink.pointer());
                        } catch (RuntimeException e) {
                            if (e.getMessage() != null && e.getMessage().contains("-2147467231")) {
                                System.out.println(
                                        "SMTC seek registration refused (E_NOTIMPL); skipping");
                                return;
                            }
                            throw e;
                        }
                        assertTrue(seekToken != 0);
                        controls2.removePlaybackPositionChangeRequested(seekToken);
                    } finally {
                        seekOwner.shutdown();
                    }
                } finally {
                    SmtcManager.release(timeline);
                }
            } finally {
                SmtcManager.release(props);
            }
        } finally {
            SmtcManager.release(second);
        }
    }

    /** Artwork bytes into a memory stream reference, exactly as the manager resolves them. */
    private Pointer thumbnailViaMemory(byte[] image) throws Exception {
        Pointer writerFactory = SmtcManager.getActivationFactory(
                SmtcManager.CLASS_DATA_WRITER, SmtcManager.IID_DATA_WRITER_FACTORY);
        assertNotNull(writerFactory);
        Pointer streamStatics = SmtcManager.getActivationFactory(
                SmtcManager.CLASS_STREAM_REF, SmtcManager.IID_STREAM_REF_STATICS);
        assertNotNull(streamStatics);
        try {
            Pointer stream = SmtcManager.activate(SmtcManager.CLASS_MEMORY_STREAM);
            assertNotNull(stream);
            try {
                Pointer random = SmtcManager.queryInterface(
                        stream, SmtcManager.IID_RANDOM_ACCESS_STREAM);
                assertNotNull(random);
                try {
                    SmtcManager.RandomAccessStream randomStream =
                            new SmtcManager.RandomAccessStream(random);
                    Pointer output = randomStream.getOutputStreamAt(0);
                    assertNotNull(output);
                    try {
                        Pointer writer = new SmtcManager.DataWriterFactory(writerFactory)
                                .createDataWriter(output);
                        assertNotNull(writer);
                        try {
                            SmtcManager.DataWriter dataWriter = new SmtcManager.DataWriter(writer);
                            dataWriter.writeBytes(image);
                            assertEquals("unstored bytes after WriteBytes", image.length,
                                    new SmtcManager.WinRtObject(writer).getInt(6));
                            Pointer operation = dataWriter.storeAsync();
                            assertNotNull(operation);
                            try {
                                SmtcManager.AsyncUint async = new SmtcManager.AsyncUint(operation);
                                long waited = 0;
                                int status = async.getStatus();
                                while (status == 0 && waited < 2_000) {
                                    Thread.sleep(10);
                                    waited += 10;
                                    status = async.getStatus();
                                }
                                assertEquals("async status=" + status, 1, status);
                                assertEquals(image.length, async.getResults());
                            } finally {
                                SmtcManager.release(operation);
                            }
                            SmtcManager.release(dataWriter.detachStream());
                        } finally {
                            SmtcManager.release(writer);
                        }
                        randomStream.seek(0);
                        return new SmtcManager.StreamRefStatics(streamStatics)
                                .createFromStream(stream);
                    } finally {
                        SmtcManager.release(output);
                    }
                } finally {
                    SmtcManager.release(random);
                }
            } finally {
                SmtcManager.release(stream);
            }
        } finally {
            SmtcManager.release(streamStatics);
            SmtcManager.release(writerFactory);
        }
    }
}
