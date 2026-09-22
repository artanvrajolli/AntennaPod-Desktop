package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class TrayIconTest {
    @Test
    public void testArtworkScaledToRequestedSize() {
        BufferedImage source = artwork(64, 64, Color.RED);
        BufferedImage icon = TrayManager.toTrayIcon(source, 16, 16);
        assertEquals(16, icon.getWidth());
        assertEquals(16, icon.getHeight());
    }

    @Test
    public void testArtworkFillsCentreAndRoundsCorners() {
        BufferedImage icon = TrayManager.toTrayIcon(artwork(64, 64, Color.RED), 16, 16);
        Color centre = new Color(icon.getRGB(8, 8), true);
        Color corner = new Color(icon.getRGB(0, 0), true);
        assertEquals(255, centre.getRed());
        assertEquals(0, centre.getGreen());
        assertEquals(255, centre.getAlpha());
        assertEquals(0, corner.getAlpha());
    }

    @Test
    public void testWideArtworkIsCentredNotStretched() {
        BufferedImage icon = TrayManager.toTrayIcon(artwork(64, 16, Color.RED), 16, 16);
        assertEquals(255, new Color(icon.getRGB(8, 8), true).getAlpha());
        assertTrue("letters above the artwork should stay transparent",
                new Color(icon.getRGB(8, 2), true).getAlpha() == 0);
        assertTrue("letters below the artwork should stay transparent",
                new Color(icon.getRGB(8, 13), true).getAlpha() == 0);
    }

    @Test
    public void testProgressBarFillsPlayedRun() {
        BufferedImage icon = TrayManager.withProgress(artwork(16, 16, Color.BLUE), 0.5);
        assertEquals(16, icon.getWidth());
        assertEquals(16, icon.getHeight());
        Color played = new Color(icon.getRGB(2, 15), true);
        assertEquals(0x1F, played.getRed());
        assertEquals(0xEB, played.getBlue());
        Color rest = new Color(icon.getRGB(13, 15), true);
        assertEquals(0, rest.getRed());
        assertTrue("unplayed run stays dark", rest.getBlue() < 128);
        // above the bar the icon is untouched
        assertEquals(255, new Color(icon.getRGB(8, 8), true).getBlue());
    }

    @Test
    public void testProgressBarEmptyAndFull() {
        Color empty = new Color(TrayManager.withProgress(artwork(16, 16, Color.BLUE), 0)
                .getRGB(8, 15), true);
        assertEquals(0, empty.getRed());
        assertTrue(empty.getBlue() < 128);
        Color full = new Color(TrayManager.withProgress(artwork(16, 16, Color.BLUE), 1)
                .getRGB(8, 15), true);
        assertEquals(0xEB, full.getBlue());
    }

    private static BufferedImage artwork(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }
}
