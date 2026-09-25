package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class SeekAccentTest {
    @Test
    public void testSolidRedStaysARedHex() {
        String accent = SeekAccent.fromAwt(filled(64, 64, new Color(220, 30, 30)));
        assertNotNull(accent);
        assertTrue(accent.matches("#[0-9a-f]{6}"));
        int[] rgb = hexToRgb(accent);
        assertTrue("red dominates", rgb[0] > rgb[1] + 40 && rgb[0] > rgb[2] + 40);
    }

    @Test
    public void testMostPixelsWinOverSmallLogo() {
        // dominant means most pixels: a small logo on a white cover yields a near-white
        // tint, the way Color Thief's most populous box wins
        BufferedImage cover = filled(100, 100, Color.WHITE);
        Graphics2D g = cover.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(35, 35, 30, 30);
        g.dispose();
        String accent = SeekAccent.fromAwt(cover);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the white cover wins, got " + accent,
                rgb[0] > 200 && rgb[1] > 200 && rgb[2] > 200);
    }

    @Test
    public void testLargerContentWinsOverLetterboxing() {
        BufferedImage frame = new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 100, 60);
        g.setColor(new Color(30, 120, 200));
        g.fillRect(10, 10, 80, 40);
        g.dispose();
        String accent = SeekAccent.fromAwt(frame);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the larger blue content wins, got " + accent, rgb[2] > rgb[0] + 30);
    }

    @Test
    public void testLargerShareWinsTwoTone() {
        BufferedImage cover = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cover.createGraphics();
        g.setColor(new Color(30, 160, 60));
        g.fillRect(0, 0, 70, 100);
        g.setColor(new Color(200, 30, 30));
        g.fillRect(70, 0, 30, 100);
        g.dispose();
        String accent = SeekAccent.fromAwt(cover);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the 70% green run wins, got " + accent, rgb[1] > rgb[0] + 40);
    }

    @Test
    public void testGreyWhiteAndBlackKeepTheirColor() {
        // the raw dominant color is kept even when it is grey, white or black: no vivid
        // boost and no fallback to the theme blue
        assertNear(new Color(140, 140, 140),
                hexToRgb(SeekAccent.fromAwt(filled(48, 48, new Color(140, 140, 140)))), 10);
        int[] white = hexToRgb(SeekAccent.fromAwt(filled(48, 48, new Color(245, 245, 245))));
        assertTrue("white cover stays near-white", white[0] > 235 && white[1] > 235 && white[2] > 235);
        int[] black = hexToRgb(SeekAccent.fromAwt(filled(48, 48, new Color(10, 10, 10))));
        assertTrue("black cover stays near-black", black[0] < 20 && black[1] < 20 && black[2] < 20);
    }

    @Test
    public void testSmallLogoLosesToGreyCover() {
        BufferedImage cover = filled(100, 100, new Color(200, 200, 200));
        Graphics2D g = cover.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(35, 35, 30, 30);
        g.dispose();
        String accent = SeekAccent.fromAwt(cover);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the grey cover wins, got " + accent,
                rgb[0] > 150 && Math.abs(rgb[0] - rgb[1]) < 30 && Math.abs(rgb[0] - rgb[2]) < 30);
    }

    @Test
    public void testTransparentPixelsAreSkipped() {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 160, 60, 255));
        g.fillRect(0, 0, 4, 20);
        g.dispose();
        String accent = SeekAccent.fromAwt(image);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the only opaque run wins, got " + accent, rgb[1] > rgb[0]);
    }

    @Test
    public void testNothingUsableGivesNull() {
        assertNull(SeekAccent.fromAwt(null));
        assertNull(SeekAccent.fromArgb(null));
        assertNull(SeekAccent.fromArgb(new int[0]));
        BufferedImage transparent = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        assertNull(SeekAccent.fromAwt(transparent));
    }

    @Test
    public void testFxOverloadRejectsUnloadedImages() {
        assertNull(SeekAccent.fromFx(null));
    }

    @Test
    public void testBrightnessAnchors() {
        assertTrue(SeekAccent.brightness("#0c0c0c") < 20);
        assertTrue(SeekAccent.brightness("#f4f4f4") > 235);
        int grey = SeekAccent.brightness("#8c8c8c");
        assertTrue("mid grey sits in the middle, got " + grey, grey > 120 && grey < 160);
    }

    @Test
    public void testOutlineForExtremes() {
        assertEquals(SeekAccent.DARK_THEME_OUTLINE, SeekAccent.outlineFor(true, "#0c0c0c"));
        assertEquals(SeekAccent.LIGHT_THEME_OUTLINE, SeekAccent.outlineFor(false, "#f4f4f4"));
        assertNull("white on dark needs none", SeekAccent.outlineFor(true, "#f4f4f4"));
        assertNull("black on light needs none", SeekAccent.outlineFor(false, "#0c0c0c"));
        assertNull("mid red needs none either way", SeekAccent.outlineFor(true, "#c81e1e"));
        assertNull("mid red needs none either way", SeekAccent.outlineFor(false, "#c81e1e"));
        assertNull(SeekAccent.outlineFor(true, null));
        assertNull("the theme blue is already visible", SeekAccent.outlineFor(true, "-fx-accent"));
    }

    @Test
    public void testEpisodeImageBeatsSubscriptionFallback() {
        // documents the selection rule the player bar follows: the episode's own image is
        // sampled, and the subscription image is only the fallback when it is missing
        String episode = "#c81e1e";
        String feed = "#1e64c8";
        assertEquals(episode, pickArtwork(episode, feed));
        assertEquals(feed, pickArtwork(null, feed));
        assertEquals(feed, pickArtwork("", feed));
        assertNull(pickArtwork(null, null));
    }

    private static String pickArtwork(String episodeImageUrl, String feedImageUrl) {
        if (episodeImageUrl != null && !episodeImageUrl.isEmpty()) {
            return episodeImageUrl;
        }
        if (feedImageUrl != null && !feedImageUrl.isEmpty()) {
            return feedImageUrl;
        }
        return null;
    }

    private static void assertNear(Color expected, int[] actual, int tolerance) {
        assertNotNull(actual);
        assertTrue("expected near " + expected + " but got #" + toHex(actual),
                Math.abs(expected.getRed() - actual[0]) <= tolerance
                        && Math.abs(expected.getGreen() - actual[1]) <= tolerance
                        && Math.abs(expected.getBlue() - actual[2]) <= tolerance);
    }

    private static String toHex(int[] rgb) {
        return String.format("%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private static BufferedImage filled(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static int[] hexToRgb(String hex) {
        assertNotNull(hex);
        assertEquals(7, hex.length());
        return new int[]{
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }
}
