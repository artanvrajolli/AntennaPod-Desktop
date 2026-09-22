package de.danoeh.antennapod.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/**
 * The window icon Windows draws on the taskbar button. While something is playing, the app icon
 * keeps the frame and the artwork of the episode is drawn into the middle of it, so the window is
 * still recognisable as AntennaPod at a glance.
 */
final class TaskbarIcon {
    /** How much of the icon's edge the artwork takes up. */
    static final double ARTWORK_FRACTION = 0.6;
    /** The size the artwork is loaded at: enough for the largest icon's inset. */
    static final int ARTWORK_SIZE = 256;
    /** Fills the inset behind artwork that does not cover it, so the app icon cannot show through. */
    private static final Color BACKING = new Color(0x20, 0x20, 0x20, 0xFF);
    /** Separates the artwork from whatever the app icon puts behind it. */
    private static final Color OUTLINE = new Color(0xFF, 0xFF, 0xFF, 0x66);

    private TaskbarIcon() {
    }

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("antennapod.desktop.icon", "true"));
    }

    /**
     * Draws the artwork into the middle of one app icon size. Artwork that is not square is
     * centred rather than stretched, the way the tray icon handles it.
     */
    static BufferedImage compose(BufferedImage base, BufferedImage artwork) {
        if (base == null) {
            return null;
        }
        if (artwork == null || artwork.getWidth() <= 0 || artwork.getHeight() <= 0) {
            return base;
        }
        int width = base.getWidth();
        int height = base.getHeight();
        BufferedImage composed = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = composed.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, width, height, null);

        int boxWidth = Math.max(1, (int) Math.round(width * ARTWORK_FRACTION));
        int boxHeight = Math.max(1, (int) Math.round(height * ARTWORK_FRACTION));
        int boxX = (width - boxWidth) / 2;
        int boxY = (height - boxHeight) / 2;
        float radius = Math.max(1f, boxWidth / 5f);
        RoundRectangle2D box =
                new RoundRectangle2D.Float(boxX, boxY, boxWidth, boxHeight, radius, radius);

        Shape clip = g.getClip();
        g.setClip(box);
        g.setColor(BACKING);
        g.fill(box);
        double scale = Math.min((double) boxWidth / artwork.getWidth(),
                (double) boxHeight / artwork.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(artwork.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(artwork.getHeight() * scale));
        g.drawImage(artwork, boxX + (boxWidth - drawWidth) / 2, boxY + (boxHeight - drawHeight) / 2,
                drawWidth, drawHeight, null);
        g.setClip(clip);

        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(Math.max(1f, width / 64f)));
        g.draw(box);
        g.dispose();
        return composed;
    }

    /** The app icon at whatever size the window or the tray asks for. */
    static BufferedImage scale(BufferedImage source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) {
            return null;
        }
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /** Reads a loaded JavaFX image into an AWT one. Returns null while the image is still loading. */
    static BufferedImage toAwt(Image image) {
        if (image == null || image.isError() || image.getProgress() < 1) {
            return null;
        }
        PixelReader reader = image.getPixelReader();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (reader == null || width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = reader.getArgb(x, y);
            }
            buffered.setRGB(0, y, width, 1, row, 0, width);
        }
        return buffered;
    }

    /** The way back, because the stage only takes JavaFX images. */
    static Image toFx(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        WritableImage fx = new WritableImage(width, height);
        PixelWriter writer = fx.getPixelWriter();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            writer.setPixels(0, y, width, 1, PixelFormat.getIntArgbInstance(), row, 0, width);
        }
        return fx;
    }
}
