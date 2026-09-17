package de.danoeh.antennapod.desktop;

import java.util.HashMap;
import java.util.Map;
import javafx.scene.image.Image;

final class ImageCache {
    private static final Map<String, Image> CACHE = new HashMap<>();

    private ImageCache() {
    }

    static Image get(String url, double width, double height) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String key = width + "x" + height + " " + url;
        synchronized (CACHE) {
            Image cached = CACHE.get(key);
            if (cached != null) {
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
