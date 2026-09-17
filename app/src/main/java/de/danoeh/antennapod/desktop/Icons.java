package de.danoeh.antennapod.desktop;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

public final class Icons {
    private static final double SIZE = 18;

    private Icons() {
    }

    public static Node icon(String content) {
        return icon(content, SIZE);
    }

    public static Node icon(String content, double size) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        double scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);
        javafx.scene.layout.Region holder = new javafx.scene.layout.Region();
        holder.setShape(path);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setStyle("-fx-background-color: -fx-text-background-color;");
        return holder;
    }

    public static Node play() {
        return icon("M8 5v14l11-7z");
    }

    public static Node pause() {
        return icon("M6 19h4V5H6v14zm8-14v14h4V5h-4z");
    }

    public static Node stop() {
        return icon("M6 6h12v12H6z");
    }

    public static Node previous() {
        return icon("M6 6h2v12H6zm3.5 6l8.5 6V6z");
    }

    public static Node next() {
        return icon("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z");
    }

    public static Node download() {
        return icon("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z");
    }

    public static Node upload() {
        return icon("M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5z");
    }

    public static Node queueAdd() {
        return icon("M14 10H3v2h11v-2zm0-4H3v2h11V6zM3 16h7v-2H3v2zm11.41 3.83c.36-.59 1-1 1.59-1"
                + " .63 0 1.2.42 1.4 1H19v2h-2.6c-.2.58-.77 1-1.4 1-.63 0-1.23-.41-1.59-1H5v-2h9.41z"
                + "M19 5v2h-2V5h-2V3h2V1h2v2h2v2h-2z");
    }

    public static Node info() {
        return icon("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z");
    }

    public static Node check() {
        return icon("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
    }

    public static Node replay() {
        return icon("M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z");
    }

    public static Node star(boolean filled) {
        return filled
                ? icon("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
                : icon("M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.64-7.03L22 9.24z"
                        + "M12 15.4V6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z");
    }

    public static Node up() {
        return icon("M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z");
    }

    public static Node down() {
        return icon("M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z");
    }

    public static Node clock() {
        return icon("M12 22c5.52 0 10-4.48 10-10S17.52 2 12 2 2 6.48 2 12s4.48 10 10 10zm0-18c4.41 0 8 3.59 8 8s-3.59 8-8 8-8-3.59-8-8 3.59-8 8-8zm.5 13H11v-6l5.25 3.15.75-1.23-4.5-2.67V17z");
    }

    public static Node remove() {
        return icon("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z");
    }

    public static Node refresh() {
        return icon("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z");
    }

    public static Node search() {
        return icon("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
    }

    public static Node settings() {
        return icon("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z");
    }

    public static Node sync() {
        return icon("M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14v3l-4-4 4-4v3c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8z");
    }

    public static Node history() {
        return icon("M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z");
    }

    public static Node stats() {
        return icon("M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z");
    }

    public static Node queue() {
        return icon("M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16v-2h8v2H3zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h2V6h-4z");
    }

    public static Node add() {
        return icon("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z");
    }

    public static Node favorite() {
        return star(true);
    }
}
