package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/**
 * The parts of the media key listener that can be checked without claiming a key off the rest of
 * the machine. The virtual key codes matter most: a wrong one registers a real key that has
 * nothing to do with playback, and the failure is silent.
 */
public class MediaKeysTest {
    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
    }

    @Test
    public void testTheVirtualKeyCodesAreTheOnesWindowsDefines() {
        // WinUser.h: VK_MEDIA_NEXT_TRACK 0xB0 .. VK_MEDIA_PLAY_PAUSE 0xB3
        assertEquals(0xB0, MediaKeys.VK_MEDIA_NEXT_TRACK);
        assertEquals(0xB1, MediaKeys.VK_MEDIA_PREV_TRACK);
        assertEquals(0xB2, MediaKeys.VK_MEDIA_STOP);
        assertEquals(0xB3, MediaKeys.VK_MEDIA_PLAY_PAUSE);
    }

    @Test
    public void testEveryKeyIsClaimedUnderItsOwnId() {
        Map<Integer, Integer> keys = MediaKeys.keysById();
        assertEquals("all four media keys", 4, keys.size());
        assertEquals(4, new HashSet<>(keys.values()).size());
        assertEquals(Integer.valueOf(MediaKeys.VK_MEDIA_PLAY_PAUSE),
                keys.get(MediaKeys.ID_PLAY_PAUSE));
        assertEquals(Integer.valueOf(MediaKeys.VK_MEDIA_NEXT_TRACK), keys.get(MediaKeys.ID_NEXT));
        assertEquals(Integer.valueOf(MediaKeys.VK_MEDIA_PREV_TRACK),
                keys.get(MediaKeys.ID_PREVIOUS));
        assertEquals(Integer.valueOf(MediaKeys.VK_MEDIA_STOP), keys.get(MediaKeys.ID_STOP));
    }

    @Test
    public void testTheIdsAreDistinct() {
        // WM_HOTKEY reports only the id, so two keys sharing one would be indistinguishable
        List<Integer> ids = new ArrayList<>(MediaKeys.keysById().keySet());
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertNotEquals(MediaKeys.ID_PLAY_PAUSE, MediaKeys.ID_STOP);
    }

    @Test
    public void testEveryIdHasAName() {
        for (int id : MediaKeys.keysById().keySet()) {
            assertNotEquals("unknown", MediaKeys.nameOf(id));
        }
        assertEquals("unknown", MediaKeys.nameOf(99));
    }

    @Test
    public void testThePropertyTurnsItOff() {
        Assume.assumeTrue("Windows only", onWindows());
        String previous = System.getProperty("antennapod.desktop.mediakeys");
        try {
            System.setProperty("antennapod.desktop.mediakeys", "false");
            assertFalse(MediaKeys.isEnabled());
            System.setProperty("antennapod.desktop.mediakeys", "true");
            assertTrue(MediaKeys.isEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty("antennapod.desktop.mediakeys");
            } else {
                System.setProperty("antennapod.desktop.mediakeys", previous);
            }
        }
    }

    @Test
    public void testNothingIsClaimedBeforeItStarts() {
        MediaKeys keys = new MediaKeys(new MediaKeys.Callbacks() {
            @Override
            public void onPlayPause() {
            }

            @Override
            public void onNext() {
            }

            @Override
            public void onPrevious() {
            }

            @Override
            public void onStop() {
            }
        });
        assertTrue(keys.claimed().isEmpty());
        assertFalse(keys.isRunning());
        // stopping something that never started must not throw
        keys.stop();
    }

    @Test
    public void testDisabledByThePropertyStartsNothing() {
        String previous = System.getProperty("antennapod.desktop.mediakeys");
        try {
            System.setProperty("antennapod.desktop.mediakeys", "false");
            MediaKeys keys = new MediaKeys(new MediaKeys.Callbacks() {
                @Override
                public void onPlayPause() {
                }

                @Override
                public void onNext() {
                }

                @Override
                public void onPrevious() {
                }

                @Override
                public void onStop() {
                }
            });
            keys.start();
            assertFalse(keys.isRunning());
            assertTrue(keys.claimed().isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty("antennapod.desktop.mediakeys");
            } else {
                System.setProperty("antennapod.desktop.mediakeys", previous);
            }
        }
    }
}
