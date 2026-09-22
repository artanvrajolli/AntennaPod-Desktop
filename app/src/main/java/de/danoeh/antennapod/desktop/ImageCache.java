package de.danoeh.antennapod.desktop;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.image.Image;

final class ImageCache {
    /** Enough for every cover in a large library at the few sizes the UI asks for. */
    static final int MAX_ENTRIES = 600;

    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ImageCache() {
    }

    static Image get(String url, double width, double height) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String key = width + "x" + height + " " + url;
        synchronized (CACHE) {
            Image cached = CACHE.get(key);
            // a load that failed (offline at startup, a CDN hiccup) is tried again rather than
            // left blank until the app restarts: JavaFX never retries a failed Image by itself
            if (cached != null && !cached.isError()) {
                return cached;
            }
        }
        Image image;
        try {
            image = new Image(url, width, height, true, true, true);
        } catch (Exception e) {
            return null;
        }
        synchronized (CACHE) {
            CACHE.put(key, image);
        }
        return image;
    }
}
