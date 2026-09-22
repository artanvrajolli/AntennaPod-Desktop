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
    public void testSmallLogoBeatsWhiteBackground() {
        BufferedImage cover = filled(100, 100, Color.WHITE);
        Graphics2D g = cover.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(35, 35, 30, 30);
        g.dispose();
        String accent = SeekAccent.fromAwt(cover);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the logo color wins, got " + accent, rgb[0] > rgb[1] + 40);
    }

    @Test
    public void testBlackBarsDoNotWinOverContent() {
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
        assertTrue("the blue content wins, got " + accent, rgb[2] > rgb[0] + 30);
    }

    @Test
    public void testGreyCoversFallBackToDefaultBlue() {
        // white, black and mid grey would sit tone-on-tone on the track in one theme or the
        // other, so they get no tint and the slider keeps the theme blue
        assertNull(SeekAccent.fromAwt(filled(48, 48, new Color(245, 245, 245))));
        assertNull(SeekAccent.fromAwt(filled(48, 48, new Color(10, 10, 10))));
        assertNull(SeekAccent.fromAwt(filled(48, 48, new Color(140, 140, 140))));
    }

    @Test
    public void testAccentNeverMatchesEitherThemeBackground() {
        Color[] covers = {
                new Color(220, 30, 30),
                new Color(30, 120, 200),
                new Color(30, 160, 60),
                new Color(230, 140, 20),
                new Color(130, 60, 180),
                new Color(20, 160, 160),
                new Color(200, 180, 20),
                new Color(200, 60, 140),
        };
        for (Color cover : covers) {
            String accent = SeekAccent.fromAwt(filled(48, 48, cover));
            assertNotNull("vivid cover keeps a tint: " + cover, accent);
            double distance = SeekAccent.minBackgroundDistance(hexToRgb(accent));
            assertTrue("tint stands clear of both themes, got " + accent
                    + " for " + cover, distance >= SeekAccent.MIN_BACKGROUND_DISTANCE);
        }
    }

    @Test
    public void testGreyLogoCoverStillFindsItsColor() {
        // a mostly-grey cover with a vivid logo still picks the logo, and the logo tint
        // itself stands clear of both themes
        BufferedImage cover = filled(100, 100, new Color(200, 200, 200));
        Graphics2D g = cover.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(35, 35, 30, 30);
        g.dispose();
        String accent = SeekAccent.fromAwt(cover);
        assertNotNull(accent);
        int[] rgb = hexToRgb(accent);
        assertTrue("the logo color wins, got " + accent, rgb[0] > rgb[1] + 40);
        assertTrue(SeekAccent.minBackgroundDistance(rgb) >= SeekAccent.MIN_BACKGROUND_DISTANCE);
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

    private static BufferedImage filled(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static int[] hexToRgb(String hex) {
        assertEquals(7, hex.length());
        return new int[]{
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }
}
