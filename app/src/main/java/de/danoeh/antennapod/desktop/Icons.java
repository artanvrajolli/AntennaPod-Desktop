package de.danoeh.antennapod.desktop;

import java.util.Locale;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/**
 * The in-app icon set: flat, solid shapes drawn on a 256 unit grid.
 *
 * <p>The glyphs are built from the primitives below rather than pasted path data, so the whole
 * set shares one geometry — the same 24 unit bar, the same 26 unit diagonal, the same corner
 * radius — and a change to a primitive carries across every icon that uses it.
 *
 * <p>Every additive primitive winds clockwise on screen and every cut-out winds counter-clockwise,
 * so overlapping parts merge under the non-zero fill rule instead of cancelling each other out.
 */
public final class Icons {
    private static final double SIZE = 15;
    private static final double VIEW_BOX = 256;
    /** Line weight for bars and strokes. */
    private static final double BAR = 24;
    /** Slightly heavier weight for diagonals, which read thinner than straight bars. */
    private static final double DIAGONAL = 26;
    /** Window caption glyphs sit at a smaller size than the toolbar set and need a lighter bar. */
    private static final double CAPTION = 14;
    /** Caption glyphs are drawn smaller than the toolbar set, as Windows draws its own. */
    private static final double CAPTION_SIZE = 11;

    private Icons() {
    }

    public static Node icon(String content) {
        return icon(content, SIZE);
    }

    public static Node icon(String content, double size) {
        return icon(content, size, VIEW_BOX);
    }

    public static Node icon(String content, double size, double viewBox) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        double scale = size / viewBox;
        path.setScaleX(scale);
        path.setScaleY(scale);
        Region holder = new Region();
        holder.setShape(path);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setStyle("-fx-background-color: -fx-text-background-color;");
        return holder;
    }

    /** Tints an icon with the theme accent, marking it as the primary action. */
    public static Node accent(Node icon) {
        return accent(icon, "-fx-accent");
    }

    /** Tints an icon with an explicit color, e.g. the artwork accent of what is playing. */
    public static Node accent(Node icon, String color) {
        icon.setStyle("-fx-background-color: " + color + ";");
        return icon;
    }

    // ---------------------------------------------------------------- transport

    public static Node play() {
        return play(SIZE);
    }

    public static Node play(double size) {
        return icon(polygon(true, 84, 50, 206, 128, 84, 206), size);
    }

    public static Node pause() {
        return pause(SIZE);
    }

    public static Node pause(double size) {
        return icon(rect(80, 56, 34, 144, 14) + rect(142, 56, 34, 144, 14), size);
    }

    public static Node stop() {
        return icon(rect(66, 66, 124, 124, 18));
    }

    public static Node previous() {
        return icon(rect(56, 56, 28, 144, 12) + polygon(true, 200, 50, 96, 128, 200, 206));
    }

    public static Node next() {
        return icon(polygon(true, 56, 50, 160, 128, 56, 206) + rect(172, 56, 28, 144, 12));
    }

    /** Circular arrow running anti-clockwise, head at the top. */
    public static Node replay10() {
        return icon(arcArrow(128, 128, 80, BAR, 250, 530, true));
    }

    /** Circular arrow running clockwise, head at the top. */
    public static Node forward30() {
        return icon(arcArrow(128, 128, 80, BAR, 10, 290, false));
    }

    public static Node replay() {
        return replay10();
    }

    // ---------------------------------------------------------------- volume

    private static String speaker(double right) {
        double cone = right - 56;
        return polygon(true, 36, 104, cone, 104, right, 50, right, 206, cone, 152, 36, 152);
    }

    public static Node volumeUp() {
        return icon(speaker(134)
                + band(130, 128, 58, 20, -46, 46)
                + band(130, 128, 92, 20, -50, 50));
    }

    public static Node volumeOff() {
        return icon(speaker(112)
                + capsule(152, 100, 214, 162, 22)
                + capsule(214, 100, 152, 162, 22));
    }

    // ---------------------------------------------------------------- chevrons

    public static Node navigateBefore() {
        return icon(capsule(158, 58, 98, 128, DIAGONAL) + capsule(98, 128, 158, 198, DIAGONAL));
    }

    public static Node navigateAfter() {
        return icon(capsule(98, 58, 158, 128, DIAGONAL) + capsule(158, 128, 98, 198, DIAGONAL));
    }

    public static Node up() {
        return icon(capsule(56, 158, 128, 90, DIAGONAL) + capsule(128, 90, 200, 158, DIAGONAL));
    }

    public static Node down() {
        return icon(capsule(56, 98, 128, 166, DIAGONAL) + capsule(128, 166, 200, 98, DIAGONAL));
    }

    // ---------------------------------------------------------------- transfers

    public static Node download() {
        return icon(capsule(128, 40, 128, 118, BAR)
                + arrowHead(128, 116, 0, 1, 52, 42)
                + capsule(52, 206, 204, 206, BAR));
    }

    public static Node upload() {
        return icon(arrowHead(128, 96, 0, -1, 52, 42)
                + capsule(128, 94, 128, 168, BAR)
                + capsule(52, 206, 204, 206, BAR));
    }

    public static Node sync() {
        return icon(capsule(46, 96, 178, 96, 22)
                + arrowHead(176, 96, 1, 0, 44, 36)
                + capsule(78, 160, 210, 160, 22)
                + arrowHead(80, 160, -1, 0, 44, 36));
    }

    public static Node refresh() {
        return icon(arcArrow(128, 128, 78, BAR, 200, 340, false)
                + arcArrow(128, 128, 78, BAR, 20, 160, false));
    }

    // ---------------------------------------------------------------- lists

    private static String listLines() {
        return capsule(48, 72, 200, 72, 22)
                + capsule(48, 128, 140, 128, 22)
                + capsule(48, 184, 140, 184, 22);
    }

    public static Node queue() {
        return icon(listLines() + polygon(true, 170, 108, 222, 150, 170, 192));
    }

    public static Node queueAdd() {
        return icon(listLines()
                + capsule(184, 126, 184, 194, 22)
                + capsule(150, 160, 218, 160, 22));
    }

    /** Three dots in a row, for the overflow menu holding the secondary actions. */
    public static Node more() {
        return icon(circle(64, 128, 22) + circle(128, 128, 22) + circle(192, 128, 22));
    }

    public static Node stats() {
        return icon(rect(40, 136, 46, 80, 14) + rect(105, 88, 46, 128, 14) + rect(170, 44, 46, 172, 14));
    }

    // ---------------------------------------------------------------- marks

    public static Node add() {
        return icon(capsule(128, 58, 128, 198, 28) + capsule(58, 128, 198, 128, 28));
    }

    public static Node remove() {
        return icon(capsule(74, 74, 182, 182, DIAGONAL) + capsule(182, 74, 74, 182, DIAGONAL));
    }

    public static Node check() {
        return icon(capsule(58, 134, 104, 180, DIAGONAL) + capsule(104, 180, 198, 74, DIAGONAL));
    }

    public static Node info() {
        return icon(ring(128, 128, 96, 20) + circle(128, 74, 14) + capsule(128, 110, 128, 186, 22));
    }

    public static Node clock() {
        return icon(ring(128, 128, 92, 22)
                + capsule(128, 128, 128, 74, 20)
                + capsule(128, 128, 170, 128, 20));
    }

    /** A clock face whose rim is an anti-clockwise arrow. */
    public static Node history() {
        return icon(arcArrow(128, 128, 88, 22, 250, 530, true)
                + capsule(128, 128, 128, 84, 20)
                + capsule(128, 128, 164, 144, 20));
    }

    public static Node search() {
        return icon(ring(110, 110, 64, BAR) + capsule(156, 156, 212, 212, 28));
    }

    /** Eight-tooth gear: a solid body, radial teeth and a punched centre. */
    public static Node settings() {
        StringBuilder teeth = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            teeth.append(capsule(128 + 50 * cos, 128 + 50 * sin, 128 + 96 * cos, 128 + 96 * sin, 38));
        }
        return icon(circle(128, 128, 76) + teeth + hole(128, 128, 30));
    }

    public static Node star(boolean filled) {
        String outline = star(128, 132, 100, 44, true);
        return icon(filled ? outline : outline + star(128, 132, 72, 31, false));
    }

    public static Node favorite() {
        return star(true);
    }

    /** Five bars rising and falling, used for the waveform and skip-silence controls. */
    public static Node wave() {
        return wave(SIZE);
    }

    public static Node wave(double size) {
        return icon(rect(28, 96, 24, 64, 12)
                + rect(70, 74, 24, 108, 12)
                + rect(112, 48, 24, 160, 12)
                + rect(154, 74, 24, 108, 12)
                + rect(196, 96, 24, 64, 12), size);
    }

    // GitHub mark, drawn on a 16 unit grid (from GitHub's own octicon, MIT licensed)
    private static final String GITHUB =
            "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49"
            + "-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23"
            + ".82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59"
            + ".82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04"
            + " 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25"
            + ".54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z";

    public static Node github() {
        return github(SIZE);
    }

    public static Node github(double size) {
        return icon(GITHUB, size, 16);
    }

    // ---------------------------------------------------------------- window caption

    /**
     * A caption glyph, sized to the shape's own proportions rather than squashed into a square.
     * {@link #icon} lets the region stretch its shape, which is right for the toolbar set because
     * those glyphs all but fill their 256 unit grid — but a minimise bar is 10 times wider than it
     * is tall, and stretching it to a square turns it into a solid block.
     */
    private static Node captionIcon(String content) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        javafx.geometry.Bounds bounds = path.getBoundsInLocal();
        // fit the glyph's own extent, not the 256 unit grid: these shapes deliberately sit well
        // inside it, and scaling by the grid would leave them at half the size they should be
        double extent = Math.max(bounds.getWidth(), bounds.getHeight());
        double scale = extent > 0 ? CAPTION_SIZE / extent : 1;
        double width = Math.max(bounds.getWidth() * scale, 1);
        double height = Math.max(bounds.getHeight() * scale, 1);
        Region holder = new Region();
        holder.setShape(path);
        holder.setMinSize(width, height);
        holder.setPrefSize(width, height);
        holder.setMaxSize(width, height);
        holder.setStyle("-fx-background-color: -fx-text-background-color;");
        return holder;
    }

    public static Node windowMinimize() {
        return captionIcon(capsule(64, 128, 192, 128, CAPTION));
    }

    public static Node windowMaximize() {
        return captionIcon(frame(64, 64, 128, 128, CAPTION, 12));
    }

    /** The front square with the corner of the one behind it peeking out, clear of the frame. */
    public static Node windowRestore() {
        return captionIcon(frame(60, 96, 110, 100, CAPTION, 10)
                + capsule(96, 70, 186, 70, CAPTION)
                + capsule(186, 70, 186, 160, CAPTION));
    }

    public static Node windowClose() {
        return captionIcon(capsule(72, 72, 184, 184, CAPTION) + capsule(184, 72, 72, 184, CAPTION));
    }

    // ---------------------------------------------------------------- primitives

    /** Rounded rectangle, wound clockwise. */
    private static String rect(double x, double y, double w, double h, double r) {
        double radius = Math.min(r, Math.min(w, h) / 2);
        return "M" + n(x + radius) + "," + n(y)
                + "H" + n(x + w - radius) + arcTo(radius, x + w, y + radius, 1)
                + "V" + n(y + h - radius) + arcTo(radius, x + w - radius, y + h, 1)
                + "H" + n(x + radius) + arcTo(radius, x, y + h - radius, 1)
                + "V" + n(y + radius) + arcTo(radius, x + radius, y, 1) + "Z";
    }

    /** Rounded rectangle wound anti-clockwise, punching a hole in the shape underneath. */
    private static String rectHole(double x, double y, double w, double h, double r) {
        double radius = Math.min(r, Math.min(w, h) / 2);
        return "M" + n(x + radius) + "," + n(y)
                + arcTo(radius, x, y + radius, 0)
                + "V" + n(y + h - radius) + arcTo(radius, x + radius, y + h, 0)
                + "H" + n(x + w - radius) + arcTo(radius, x + w, y + h - radius, 0)
                + "V" + n(y + radius) + arcTo(radius, x + w - radius, y, 0) + "Z";
    }

    /** Rounded rectangle outline: an outer rect with a smaller one punched out of the middle. */
    private static String frame(double x, double y, double w, double h, double thickness, double r) {
        return rect(x, y, w, h, r)
                + rectHole(x + thickness, y + thickness, w - 2 * thickness, h - 2 * thickness,
                        Math.max(r - thickness, 0));
    }

    /** Filled circle, wound clockwise so it adds to whatever it overlaps. */
    private static String circle(double cx, double cy, double r) {
        return disc(cx, cy, r, 1);
    }

    /** Filled circle wound anti-clockwise, punching a hole in the shape underneath. */
    private static String hole(double cx, double cy, double r) {
        return disc(cx, cy, r, 0);
    }

    private static String disc(double cx, double cy, double r, int sweep) {
        return "M" + n(cx + r) + "," + n(cy)
                + "A" + n(r) + "," + n(r) + " 0 1 " + sweep + " " + n(cx - r) + "," + n(cy)
                + "A" + n(r) + "," + n(r) + " 0 1 " + sweep + " " + n(cx + r) + "," + n(cy) + "Z";
    }

    private static String ring(double cx, double cy, double r, double thickness) {
        return circle(cx, cy, r + thickness / 2) + hole(cx, cy, r - thickness / 2);
    }

    /** A bar of the given thickness between two points, with semicircular caps. */
    private static String capsule(double x1, double y1, double x2, double y2, double thickness) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        double half = thickness / 2;
        if (length < 0.0001) {
            return circle(x1, y1, half);
        }
        double nx = -dy / length * half;
        double ny = dx / length * half;
        return "M" + n(x1 + nx) + "," + n(y1 + ny)
                + arcTo(half, x1 - nx, y1 - ny, 1)
                + "L" + n(x2 - nx) + "," + n(y2 - ny)
                + arcTo(half, x2 + nx, y2 + ny, 1)
                + "Z";
    }

    /** A thick arc band sweeping clockwise from one angle to the next. */
    private static String band(double cx, double cy, double r, double thickness,
            double startDeg, double endDeg) {
        double outer = r + thickness / 2;
        double inner = r - thickness / 2;
        int large = Math.abs(endDeg - startDeg) > 180 ? 1 : 0;
        return "M" + point(cx, cy, outer, startDeg)
                + "A" + n(outer) + "," + n(outer) + " 0 " + large + " 1 " + point(cx, cy, outer, endDeg)
                + "L" + point(cx, cy, inner, endDeg)
                + "A" + n(inner) + "," + n(inner) + " 0 " + large + " 0 " + point(cx, cy, inner, startDeg)
                + "Z";
    }

    /** An arc band with a triangular head at one end, pointing the way the arc travels. */
    private static String arcArrow(double cx, double cy, double r, double thickness,
            double startDeg, double endDeg, boolean headAtStart) {
        double headDeg = headAtStart ? startDeg : endDeg;
        double angle = Math.toRadians(headDeg);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        // Tangent of a clockwise sweep; the head faces backwards when it sits at the start.
        double dirX = headAtStart ? sin : -sin;
        double dirY = headAtStart ? -cos : cos;
        return band(cx, cy, r, thickness, startDeg, endDeg)
                + arrowHead(cx + r * cos, cy + r * sin, dirX, dirY, 44, 40);
    }

    /** Triangular arrow head whose tip sits {@code length} beyond the given point. */
    private static String arrowHead(double x, double y, double dirX, double dirY,
            double length, double half) {
        double norm = Math.hypot(dirX, dirY);
        double ux = dirX / norm;
        double uy = dirY / norm;
        return polygon(true,
                x + ux * length, y + uy * length,
                x - uy * half, y + ux * half,
                x + uy * half, y - ux * half);
    }

    private static String star(double cx, double cy, double outer, double inner, boolean clockwise) {
        double[] points = new double[20];
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? outer : inner;
            double angle = Math.toRadians(-90 + i * 36);
            points[i * 2] = cx + r * Math.cos(angle);
            points[i * 2 + 1] = cy + r * Math.sin(angle);
        }
        return polygon(clockwise, points);
    }

    /**
     * Straight-edged shape through the given x,y pairs, wound clockwise on screen when
     * {@code clockwise} is set and anti-clockwise (a cut-out) when it is not.
     */
    private static String polygon(boolean clockwise, double... points) {
        double area = 0;
        for (int i = 0; i < points.length; i += 2) {
            int next = (i + 2) % points.length;
            area += (points[next] - points[i]) * (points[next + 1] + points[i + 1]);
        }
        // On a y-down canvas the shoelace sum is negative for a clockwise loop.
        boolean reverse = clockwise != (area < 0);
        StringBuilder path = new StringBuilder();
        for (int step = 0; step < points.length / 2; step++) {
            int i = reverse ? points.length / 2 - 1 - step : step;
            path.append(step == 0 ? "M" : "L").append(n(points[i * 2])).append(',').append(n(points[i * 2 + 1]));
        }
        return path.append('Z').toString();
    }

    private static String arcTo(double r, double x, double y, int sweep) {
        return "A" + n(r) + "," + n(r) + " 0 0 " + sweep + " " + n(x) + "," + n(y);
    }

    private static String point(double cx, double cy, double r, double deg) {
        double angle = Math.toRadians(deg);
        return n(cx + r * Math.cos(angle)) + "," + n(cy + r * Math.sin(angle));
    }

    private static String n(double value) {
        String text = String.format(Locale.US, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "-0".equals(text) ? "0" : text;
    }
}
