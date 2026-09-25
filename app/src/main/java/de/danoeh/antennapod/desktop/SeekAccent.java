package de.danoeh.antennapod.desktop;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/**
 * The seek bar's accent: the played run and the thumb dot follow the artwork of what is playing.
 *
 * <p>The artwork is the episode's own image, or the subscription's when the episode brings none
 * (see {@code DesktopApp.nowPlayingArtUrl}); when there is no artwork the slider keeps the
 * theme's own blue ({@code -fx-accent}), so this helper only ever produces a hex color for a
 * real image and returns null when there is nothing usable to sample.
 *
 * <p>The color is the artwork's dominant color, extracted the way
 * <a href="https://lokeshdhakar.com/projects/color-thief/">Color Thief</a> does it: pixels are
 * quantized to 5 bits per channel and the RGB cube is repeatedly split at the median along its
 * longest axis until five boxes remain; the most populous box's average wins. Only transparent
 * pixels are skipped, so the winner is the true dominant color even when it is grey, white or
 * black - there is deliberately no vivid-boost, no reshaping, and no fallback to the theme
 * blue. Returns null only when there is nothing usable to sample.
 */
final class SeekAccent {
    /** Bits kept per channel during quantization, as in Color Thief's RGB quantizer. */
    private static final int SIG_BITS = 5;
    private static final int R_SHIFT = 8 - SIG_BITS;
    private static final int HISTO_SIZE = 1 << (3 * SIG_BITS);
    private static final int SIG_RANGE = 1 << SIG_BITS;
    /** Color Thief's dominant-color path quantizes to five colors and takes the largest. */
    private static final int MAX_COLORS = 5;
    /** Below this alpha a pixel counts as transparent and is skipped, as in Color Thief. */
    private static final int ALPHA_THRESHOLD = 125;
    /** Sampled down to about this many pixels, so a 256px cover stays cheap. */
    private static final int MAX_SAMPLED_PIXELS = 4096;

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
     * Returns the dominant color of raw ARGB pixels as a hex string. Null only when there is
     * nothing to sample: null or empty input, or every pixel transparent.
     */
    static String fromArgb(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return null;
        }
        int[] histo = new int[HISTO_SIZE];
        int total = 0;
        for (int argb : pixels) {
            if (((argb >>> 24) & 0xFF) < ALPHA_THRESHOLD) {
                continue;
            }
            histo[colorIndex((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)]++;
            total++;
        }
        if (total == 0) {
            return null;
        }
        List<VBox> boxes = new ArrayList<>();
        boxes.add(VBox.tight(histo));
        while (boxes.size() < MAX_COLORS) {
            VBox biggest = null;
            for (VBox box : boxes) {
                if (box.splittable && box.count > 1
                        && (biggest == null || box.count > biggest.count)) {
                    biggest = box;
                }
            }
            if (biggest == null) {
                break;
            }
            VBox[] split = biggest.split(histo);
            if (split == null) {
                biggest.splittable = false;
                continue;
            }
            boxes.remove(biggest);
            boxes.add(split[0]);
            boxes.add(split[1]);
        }
        VBox dominant = boxes.get(0);
        for (VBox box : boxes) {
            if (box.count > dominant.count) {
                dominant = box;
            }
        }
        return toHex(dominant.average(histo));
    }

    private static int colorIndex(int r, int g, int b) {
        return ((r >> R_SHIFT) << (2 * SIG_BITS)) | ((g >> R_SHIFT) << SIG_BITS) | (b >> R_SHIFT);
    }

    /** One RGB cube under quantization; ranges are inclusive 5-bit channels. */
    private static final class VBox {
        int r1;
        int r2;
        int g1;
        int g2;
        int b1;
        int b2;
        int count;
        boolean splittable = true;

        /** The smallest box holding every color the histogram saw. */
        static VBox tight(int[] histo) {
            VBox box = new VBox();
            box.r1 = SIG_RANGE - 1;
            box.g1 = SIG_RANGE - 1;
            box.b1 = SIG_RANGE - 1;
            box.r2 = 0;
            box.g2 = 0;
            box.b2 = 0;
            for (int r = 0; r < SIG_RANGE; r++) {
                for (int g = 0; g < SIG_RANGE; g++) {
                    for (int b = 0; b < SIG_RANGE; b++) {
                        if (histo[(r << (2 * SIG_BITS)) | (g << SIG_BITS) | b] > 0) {
                            if (r < box.r1) {
                                box.r1 = r;
                            }
                            if (r > box.r2) {
                                box.r2 = r;
                            }
                            if (g < box.g1) {
                                box.g1 = g;
                            }
                            if (g > box.g2) {
                                box.g2 = g;
                            }
                            if (b < box.b1) {
                                box.b1 = b;
                            }
                            if (b > box.b2) {
                                box.b2 = b;
                            }
                        }
                    }
                }
            }
            box.count = sum(histo, box);
            return box;
        }

        /**
         * Splits at the median along the longest axis, nudged so neither half ends up empty.
         * Null when the box holds fewer than two pixels or no clean split exists (for example
         * a solid color, which is already one box).
         */
        VBox[] split(int[] histo) {
            if (count < 2) {
                return null;
            }
            int rw = r2 - r1 + 1;
            int gw = g2 - g1 + 1;
            int bw = b2 - b1 + 1;
            int axis = rw >= gw && rw >= bw ? 0 : gw >= rw && gw >= bw ? 1 : 2;
            int len = axis == 0 ? rw : axis == 1 ? gw : bw;
            if (len < 2) {
                // a single plane (for example a solid color): already one box
                return null;
            }
            int[] partial = new int[len];
            int run = 0;
            for (int i = 0; i < len; i++) {
                run += sliceSum(histo, axis, plane(axis, i));
                partial[i] = run;
            }
            if (run != count || run < 2) {
                return null;
            }
            for (int i = 0; i < len; i++) {
                if (partial[i] > run / 2) {
                    int cut = adjustCut(partial, i, len - 1, run);
                    if (cut < 0) {
                        return null;
                    }
                    VBox first = copy();
                    VBox second = copy();
                    if (axis == 0) {
                        first.r2 = plane(axis, cut);
                        second.r1 = plane(axis, cut) + 1;
                    } else if (axis == 1) {
                        first.g2 = plane(axis, cut);
                        second.g1 = plane(axis, cut) + 1;
                    } else {
                        first.b2 = plane(axis, cut);
                        second.b1 = plane(axis, cut) + 1;
                    }
                    first.count = sum(histo, first);
                    second.count = sum(histo, second);
                    if (first.count == 0 || second.count == 0) {
                        return null;
                    }
                    return new VBox[]{first, second};
                }
            }
            return null;
        }

        /**
         * Nudges the median cut toward the longer side (as Color Thief does) and then away
         * from empty planes, so both halves keep pixels. -1 when no clean split exists.
         */
        private int adjustCut(int[] partial, int at, int last, int total) {
            int left = at;
            int right = last - at;
            int cut = left <= right ? Math.min(last - 1, at + right / 2) : Math.max(0, at - 1 - left / 2);
            while (cut < last && partial[cut] == 0) {
                cut++;
            }
            while (cut > 0 && total - partial[cut] == 0 && partial[cut - 1] > 0) {
                cut--;
            }
            if (cut < 0 || cut >= last || partial[cut] == 0 || total - partial[cut] == 0) {
                for (int d = 0; d < last; d++) {
                    if (partial[d] > 0 && total - partial[d] > 0) {
                        return d;
                    }
                }
                return -1;
            }
            return cut;
        }

        /** The box's average color, from 5-bit bin centers back to 8-bit channels. */
        int[] average(int[] histo) {
            double rsum = 0;
            double gsum = 0;
            double bsum = 0;
            long ntot = 0;
            int mult = 1 << R_SHIFT;
            for (int r = r1; r <= r2; r++) {
                for (int g = g1; g <= g2; g++) {
                    for (int b = b1; b <= b2; b++) {
                        int h = histo[(r << (2 * SIG_BITS)) | (g << SIG_BITS) | b];
                        if (h > 0) {
                            ntot += h;
                            rsum += h * (r + 0.5) * mult;
                            gsum += h * (g + 0.5) * mult;
                            bsum += h * (b + 0.5) * mult;
                        }
                    }
                }
            }
            if (ntot > 0) {
                return new int[]{
                        clamp((int) Math.round(rsum / ntot)),
                        clamp((int) Math.round(gsum / ntot)),
                        clamp((int) Math.round(bsum / ntot))};
            }
            return new int[]{
                    (r1 + r2 + 1) * mult / 2,
                    (g1 + g2 + 1) * mult / 2,
                    (b1 + b2 + 1) * mult / 2};
        }

        private int plane(int axis, int i) {
            return axis == 0 ? r1 + i : axis == 1 ? g1 + i : b1 + i;
        }

        private int sliceSum(int[] histo, int axis, int coord) {
            int sum = 0;
            if (axis == 0) {
                for (int g = g1; g <= g2; g++) {
                    for (int b = b1; b <= b2; b++) {
                        sum += histo[(coord << (2 * SIG_BITS)) | (g << SIG_BITS) | b];
                    }
                }
            } else if (axis == 1) {
                for (int r = r1; r <= r2; r++) {
                    for (int b = b1; b <= b2; b++) {
                        sum += histo[(r << (2 * SIG_BITS)) | (coord << SIG_BITS) | b];
                    }
                }
            } else {
                for (int r = r1; r <= r2; r++) {
                    for (int g = g1; g <= g2; g++) {
                        sum += histo[(r << (2 * SIG_BITS)) | (g << SIG_BITS) | coord];
                    }
                }
            }
            return sum;
        }

        private static int sum(int[] histo, VBox box) {
            int sum = 0;
            for (int r = box.r1; r <= box.r2; r++) {
                for (int g = box.g1; g <= box.g2; g++) {
                    for (int b = box.b1; b <= box.b2; b++) {
                        sum += histo[(r << (2 * SIG_BITS)) | (g << SIG_BITS) | b];
                    }
                }
            }
            return sum;
        }

        private VBox copy() {
            VBox box = new VBox();
            box.r1 = r1;
            box.r2 = r2;
            box.g1 = g1;
            box.g2 = g2;
            box.b1 = b1;
            box.b2 = b2;
            box.count = count;
            return box;
        }
    }

    static String toHex(int[] rgb) {
        return String.format(Locale.US, "#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
