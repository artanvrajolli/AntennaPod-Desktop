package de.danoeh.antennapod.desktop;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/**
 * The seek bar's accent: the played run and the thumb dot follow the artwork of what is playing.
 *
 * <p>The artwork is the episode's own image, or the subscription's when the episode brings none
 * (see {@code DesktopApp.nowPlayingArtUrl}); when there is no artwork the slider keeps the
 * theme's own blue ({@code -fx-accent}), so this helper only ever produces a hex color for a
 * real image and returns null when there is nothing usable to sample.
 */
final class SeekAccent {
    /** Pixels are quantized to this many bits per channel before they are counted. */
    private static final int QUANTIZE_SHIFT = 4;
    /** A bin this saturated counts as vivid; vivid wins over a plain background. */
    private static final float VIVID_MIN_SATURATION = 0.3f;
    private static final float VIVID_MIN_VALUE = 0.2f;
    private static final float VIVID_MAX_VALUE = 0.95f;
    /** Below this the winner is treated as grey: no tint, the theme blue stays. */
    private static final float GREY_MAX_SATURATION = 0.25f;
    /** A vivid run only needs this share of the image to beat a white or black background. */
    private static final double VIVID_MIN_SHARE = 0.05;
    private static final int VIVID_MIN_PIXELS = 10;
    /** Sampled down to about this many pixels, so a 256px cover stays cheap. */
    private static final int MAX_SAMPLED_PIXELS = 4096;
    /**
     * Every surface the played run and the thumb sit on, in both themes: the app background
     * behind the bar plus the fetched and unplayed runs of the track. An accent has to stand
     * clear of all of them, so a cover that only offers a matching grey gets no tint at all.
     */
    static final int[][] BACKGROUNDS = {
            {0x1E, 0x1E, 0x1E},
            {0x5F, 0x5F, 0x5F},
            {0x8D, 0x8D, 0x8D},
            {0xEC, 0xEC, 0xEC},
            {0xC9, 0xC9, 0xC9},
            {0x9E, 0x9E, 0x9E},
            {0xFF, 0xFF, 0xFF},
    };
    /** Minimum RGB distance from every background above; below it the theme blue stays. */
    static final double MIN_BACKGROUND_DISTANCE = 90.0;

    private SeekAccent() {
    }

    /** Samples a loaded JavaFX image. Returns null while it loads, on error, or when empty. */
    static String fromFx(Image image) {
        if (image == null || image.isError() || image.getProgress() < 1) {
            return null;
        }
        PixelReader reader;
        try {
            reader = image.getPixelReader();
        } catch (Exception e) {
            return null;
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (reader == null || width <= 0 || height <= 0) {
            return null;
        }
        int step = (int) Math.sqrt((width * (double) height) / MAX_SAMPLED_PIXELS);
        step = Math.max(1, step);
        int[] pixels = new int[((width + step - 1) / step) * ((height + step - 1) / step)];
        int count = 0;
        try {
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    pixels[count++] = reader.getArgb(x, y);
                }
            }
        } catch (Exception e) {
            return null;
        }
        int[] sampled = new int[count];
        System.arraycopy(pixels, 0, sampled, 0, count);
        return fromArgb(sampled);
    }

    /** Samples an AWT image, the path unit tests exercise without a JavaFX toolkit. */
    static String fromAwt(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return fromArgb(downsample(pixels, width, height));
    }

    private static int[] downsample(int[] pixels, int width, int height) {
        int step = (int) Math.sqrt((width * (double) height) / MAX_SAMPLED_PIXELS);
        step = Math.max(1, step);
        if (step == 1) {
            return pixels;
        }
        int sampledWidth = (width + step - 1) / step;
        int sampledHeight = (height + step - 1) / step;
        int[] sampled = new int[sampledWidth * sampledHeight];
        int count = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                sampled[count++] = pixels[y * width + x];
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(sampled, 0, trimmed, 0, count);
        return trimmed;
    }

    /**
     * Picks the dominant color out of raw ARGB pixels. Transparent pixels are skipped; a vivid
     * run beats a plain background (white letterboxing, black bars) from a small share on;
     * otherwise the most common bin wins. Grey winners and anything that would blend into the
     * track or the app background in either theme give null, so the slider keeps its blue.
     */
    static String fromArgb(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return null;
        }
        Map<Integer, long[]> all = new HashMap<>();
        Map<Integer, long[]> vivid = new HashMap<>();
        long total = 0;
        long vividTotal = 0;
        for (int argb : pixels) {
            if (((argb >>> 24) & 0xFF) < 128) {
                continue;
            }
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int key = ((r >> QUANTIZE_SHIFT) << 8)
                    | ((g >> QUANTIZE_SHIFT) << 4)
                    | (b >> QUANTIZE_SHIFT);
            accumulate(all, key, r, g, b);
            total++;
            float[] hsb = rgbToHsb(r, g, b);
            if (hsb[1] >= VIVID_MIN_SATURATION
                    && hsb[2] >= VIVID_MIN_VALUE
                    && hsb[2] <= VIVID_MAX_VALUE) {
                accumulate(vivid, key, r, g, b);
                vividTotal++;
            }
        }
        if (total == 0) {
            return null;
        }
        Map<Integer, long[]> pool = all;
        if (vividTotal >= Math.max(VIVID_MIN_PIXELS, total * VIVID_MIN_SHARE)) {
            pool = vivid;
        }
        long[] best = null;
        long bestCount = -1;
        for (long[] entry : pool.values()) {
            if (entry[0] > bestCount) {
                bestCount = entry[0];
                best = entry;
            }
        }
        if (best == null || bestCount <= 0) {
            return null;
        }
        int r = (int) (best[1] / bestCount);
        int g = (int) (best[2] / bestCount);
        int b = (int) (best[3] / bestCount);
        float[] hsb = rgbToHsb(r, g, b);
        if (hsb[1] < GREY_MAX_SATURATION) {
            // white, black and grey covers would sit tone-on-tone on the track in one theme
            // or the other, so they keep the theme blue instead of a matching grey
            return null;
        }
        int[] accent = fitToBackgrounds(hsb[0], hsb[1], hsb[2]);
        return accent == null ? null : toHex(accent);
    }

    private static void accumulate(Map<Integer, long[]> bins, int key, int r, int g, int b) {
        long[] entry = bins.get(key);
        if (entry == null) {
            entry = new long[4];
            bins.put(key, entry);
        }
        entry[0]++;
        entry[1] += r;
        entry[2] += g;
        entry[3] += b;
    }

    /**
     * Makes the sampled color read as a control accent on both themes: it keeps its hue but is
     * pushed into a vivid, mid-bright band, then nudged darker or lighter until it stands clear
     * of every track and app background above. Returns null when no brightness does, so the
     * slider keeps its blue rather than wearing a matching tone.
     */
    static int[] adjust(int r, int g, int b) {
        float[] hsb = rgbToHsb(r, g, b);
        if (hsb[1] < GREY_MAX_SATURATION) {
            return null;
        }
        return fitToBackgrounds(hsb[0], hsb[1], hsb[2]);
    }

    private static int[] fitToBackgrounds(float hue, float saturation, float value) {
        saturation = Math.max(saturation, 0.55f);
        float base = clamp(value, 0.50f, 0.90f);
        float[] steps = {0f, -0.05f, 0.05f, -0.10f, 0.10f, -0.15f, 0.15f,
                -0.20f, 0.20f, -0.25f, 0.25f};
        for (float step : steps) {
            float candidate = clamp(base + step, 0.35f, 0.95f);
            int[] rgb = hsbToRgb(hue, saturation, candidate);
            if (minBackgroundDistance(rgb) >= MIN_BACKGROUND_DISTANCE) {
                return rgb;
            }
        }
        return null;
    }

    /** Closest any background above comes to this color, as RGB distance. */
    static double minBackgroundDistance(int[] rgb) {
        double closest = Double.MAX_VALUE;
        for (int[] background : BACKGROUNDS) {
            double dr = rgb[0] - background[0];
            double dg = rgb[1] - background[1];
            double db = rgb[2] - background[2];
            closest = Math.min(closest, Math.sqrt(dr * dr + dg * dg + db * db));
        }
        return closest;
    }

    static String toHex(int[] rgb) {
        return String.format(Locale.US, "#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float[] rgbToHsb(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float brightness = max;
        float saturation = max == 0 ? 0 : (max - min) / max;
        float hue = 0;
        if (max != min) {
            if (max == rf) {
                hue = (gf - bf) / (max - min);
            } else if (max == gf) {
                hue = 2f + (bf - rf) / (max - min);
            } else {
                hue = 4f + (rf - gf) / (max - min);
            }
            hue /= 6f;
            if (hue < 0) {
                hue += 1f;
            }
        }
        return new float[]{hue, saturation, brightness};
    }

    private static int[] hsbToRgb(float hue, float saturation, float brightness) {
        float r = 0;
        float g = 0;
        float bl = 0;
        if (saturation == 0) {
            r = brightness;
            g = brightness;
            bl = brightness;
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6f;
            int sector = (int) h;
            float fraction = h - sector;
            float p = brightness * (1f - saturation);
            float q = brightness * (1f - saturation * fraction);
            float t = brightness * (1f - saturation * (1f - fraction));
            switch (sector) {
                case 0:
                    r = brightness;
                    g = t;
                    bl = p;
                    break;
                case 1:
                    r = q;
                    g = brightness;
                    bl = p;
                    break;
                case 2:
                    r = p;
                    g = brightness;
                    bl = t;
                    break;
                case 3:
                    r = p;
                    g = q;
                    bl = brightness;
                    break;
                case 4:
                    r = t;
                    g = p;
                    bl = brightness;
                    break;
                default:
                    r = brightness;
                    g = p;
                    bl = q;
                    break;
            }
        }
        return new int[]{
                Math.max(0, Math.min(255, Math.round(r * 255))),
                Math.max(0, Math.min(255, Math.round(g * 255))),
                Math.max(0, Math.min(255, Math.round(bl * 255)))};
    }
}
