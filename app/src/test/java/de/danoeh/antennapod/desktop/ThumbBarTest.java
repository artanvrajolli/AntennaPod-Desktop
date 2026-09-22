package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.sun.jna.Native;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.Assume;
import org.junit.Test;

/**
 * The parts of the thumbnail toolbar that can be checked without a window. The struct layout
 * matters most: ITaskbarList3 reads THUMBBUTTON by offset, so a field in the wrong place is not a
 * wrong picture, it is a native read into the wrong memory.
 */
public class ThumbBarTest {
    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.US).contains("win");
    }

    @Test
    public void testThumbButtonMatchesTheLayoutTheShellExpects() {
        Assume.assumeTrue("Windows only", onWindows());
        ThumbBar.THUMBBUTTON button = new ThumbBar.THUMBBUTTON();
        assertEquals("szTip is WCHAR[260]", 260, button.szTip.length);
        // dwMask, iId, iBitmap (12) + padding to align the HICON pointer + hIcon
        // + szTip (520) + dwFlags (4), rounded up to the struct's alignment
        int expected = Native.POINTER_SIZE == 8 ? 552 : 540;
        assertEquals("THUMBBUTTON size", expected, button.size());
    }

    @Test
    public void testEveryGlyphDrawsSomething() {
        for (ThumbBar.Glyph glyph : ThumbBar.Glyph.values()) {
            BufferedImage image = ThumbBar.draw(24, Color.WHITE, glyph);
            assertEquals(24, image.getWidth());
            assertEquals(24, image.getHeight());
            assertTrue(glyph + " drew nothing", opaquePixels(image) > 0);
        }
    }

    @Test
    public void testTheGlyphsAreDistinguishable() {
        BufferedImage play = ThumbBar.draw(24, Color.WHITE, ThumbBar.Glyph.PLAY);
        BufferedImage pause = ThumbBar.draw(24, Color.WHITE, ThumbBar.Glyph.PAUSE);
        BufferedImage previous = ThumbBar.draw(24, Color.WHITE, ThumbBar.Glyph.PREVIOUS);
        BufferedImage next = ThumbBar.draw(24, Color.WHITE, ThumbBar.Glyph.NEXT);
        BufferedImage silenceOn = ThumbBar.draw(24, Color.WHITE, ThumbBar.Glyph.SILENCE_ON);
        BufferedImage silenceOff = ThumbBar.draw(24, Color.WHITE, ThumbBar.Glyph.SILENCE_OFF);
        assertNotEquals("play and pause must not be the same picture",
                signature(play), signature(pause));
        assertNotEquals("previous and next must be mirrored, not identical",
                signature(previous), signature(next));
        assertNotEquals(signature(play), signature(next));
        assertNotEquals("silence on and off must read differently",
                signature(silenceOn), signature(silenceOff));
        assertNotEquals("silence must not look like transport",
                signature(silenceOn), signature(play));
    }

    @Test
    public void testDisabledBySystemProperty() {
        String previous = System.getProperty("antennapod.desktop.thumbbar");
        try {
            System.setProperty("antennapod.desktop.thumbbar", "false");
            assertFalse(ThumbBar.isEnabled());
            System.clearProperty("antennapod.desktop.thumbbar");
            assertTrue("on by default", ThumbBar.isEnabled());
        } finally {
            if (previous != null) {
                System.setProperty("antennapod.desktop.thumbbar", previous);
            }
        }
    }

    @Test
    public void testInstallOnNothingIsRefusedRatherThanThrowing() {
        assertEquals(null, ThumbBar.install(null, null, null, Runnable::run));
    }

    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 128) {
                    count++;
                }
            }
        }
        return count;
    }

    /** A cheap fingerprint of where the ink sits, enough to tell the glyphs apart. */
    private static String signature(BufferedImage image) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                text.append((image.getRGB(x, y) >>> 24) > 128 ? '#' : '.');
            }
        }
        return text.toString();
    }
}
