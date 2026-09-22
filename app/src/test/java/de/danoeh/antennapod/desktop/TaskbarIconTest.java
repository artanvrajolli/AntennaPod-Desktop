package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class TaskbarIconTest {
    @Test
    public void testComposedIconKeepsTheAppIconSize() {
        BufferedImage icon = TaskbarIcon.compose(filled(32, 32, Color.BLUE),
                filled(256, 256, Color.RED));
        assertEquals(32, icon.getWidth());
        assertEquals(32, icon.getHeight());
    }

    @Test
    public void testArtworkSitsInTheMiddleAndTheAppIconKeepsTheEdges() {
        BufferedImage icon = TaskbarIcon.compose(filled(64, 64, Color.BLUE),
                filled(256, 256, Color.RED));
        Color centre = new Color(icon.getRGB(32, 32), true);
        assertEquals("the artwork belongs in the middle", 255, centre.getRed());
        assertEquals(0, centre.getBlue());
        Color corner = new Color(icon.getRGB(2, 2), true);
        assertEquals("the app icon has to stay visible around it", 255, corner.getBlue());
        assertEquals(0, corner.getRed());
    }

    @Test
    public void testWideArtworkIsCentredNotStretched() {
        BufferedImage icon = TaskbarIcon.compose(filled(64, 64, Color.BLUE),
                filled(256, 64, Color.RED));
        assertEquals(255, new Color(icon.getRGB(32, 32), true).getRed());
        // the artwork is letterboxed inside its box, and the backing covers what it leaves
        Color aboveArtwork = new Color(icon.getRGB(32, 21), true);
        assertEquals(255, aboveArtwork.getAlpha());
        assertTrue("letterboxing must not show the app icon through it",
                aboveArtwork.getBlue() < 128);
    }

    @Test
    public void testArtworkStaysInsideItsBox() {
        int size = 64;
        BufferedImage icon = TaskbarIcon.compose(filled(size, size, Color.BLUE),
                filled(256, 256, Color.RED));
        int boxEdge = (int) Math.round(size * (1 - TaskbarIcon.ARTWORK_FRACTION) / 2);
        for (int x = 0; x < boxEdge - 1; x++) {
            assertEquals("column " + x + " is outside the artwork box",
                    0, new Color(icon.getRGB(x, size / 2), true).getRed());
        }
    }

    @Test
    public void testMissingArtworkLeavesTheAppIconAlone() {
        BufferedImage base = filled(32, 32, Color.BLUE);
        assertSame(base, TaskbarIcon.compose(base, null));
        assertNull(TaskbarIcon.compose(null, filled(32, 32, Color.RED)));
    }

    @Test
    public void testScalingToTraySize() {
        BufferedImage base = filled(32, 32, Color.BLUE);
        assertSame("nothing to do at the size it already is", base, TaskbarIcon.scale(base, 32, 32));
        BufferedImage small = TaskbarIcon.scale(base, 16, 16);
        assertEquals(16, small.getWidth());
        assertEquals(16, small.getHeight());
        assertEquals(255, new Color(small.getRGB(8, 8), true).getBlue());
        assertNull(TaskbarIcon.scale(null, 16, 16));
        assertNull(TaskbarIcon.scale(base, 0, 16));
    }

    @Test
    public void testTraySizedIconStillShowsBoth() {
        BufferedImage icon = TaskbarIcon.compose(
                TaskbarIcon.scale(filled(32, 32, Color.BLUE), 16, 16), filled(256, 256, Color.RED));
        assertEquals(16, icon.getWidth());
        assertEquals(255, new Color(icon.getRGB(8, 8), true).getRed());
        assertEquals(255, new Color(icon.getRGB(0, 8), true).getBlue());
    }

    @Test
    public void testNoImageToConvert() {
        assertNull(TaskbarIcon.toAwt(null));
        assertNull(TaskbarIcon.toFx(null));
    }

    private static BufferedImage filled(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }
}
