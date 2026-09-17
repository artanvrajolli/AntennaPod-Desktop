package android.webkit;

import java.io.File;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MimeTypeMap {
    private static final MimeTypeMap INSTANCE = new MimeTypeMap();

    private static final Map<String, String> EXTRA_TYPES = new HashMap<>();

    static {
        EXTRA_TYPES.put("mp3", "audio/mpeg");
        EXTRA_TYPES.put("m4a", "audio/mp4");
        EXTRA_TYPES.put("m4b", "audio/mp4");
        EXTRA_TYPES.put("aac", "audio/aac");
        EXTRA_TYPES.put("ogg", "audio/ogg");
        EXTRA_TYPES.put("oga", "audio/ogg");
        EXTRA_TYPES.put("opus", "audio/opus");
        EXTRA_TYPES.put("wav", "audio/wav");
        EXTRA_TYPES.put("flac", "audio/flac");
        EXTRA_TYPES.put("mp4", "video/mp4");
        EXTRA_TYPES.put("m4v", "video/mp4");
        EXTRA_TYPES.put("webm", "video/webm");
        EXTRA_TYPES.put("xml", "text/xml");
        EXTRA_TYPES.put("rss", "application/rss+xml");
    }

    private MimeTypeMap() {
    }

    public static MimeTypeMap getSingleton() {
        return INSTANCE;
    }

    public String getMimeTypeFromExtension(String extension) {
        if (extension == null) {
            return null;
        }
        String lower = extension.toLowerCase(Locale.ROOT);
        if (EXTRA_TYPES.containsKey(lower)) {
            return EXTRA_TYPES.get(lower);
        }
        FileNameMap map = URLConnection.getFileNameMap();
        return map.getContentTypeFor("file." + lower);
    }

    public static String getFileExtensionFromUrl(String url) {
        if (url == null) {
            return "";
        }
        int fragment = url.lastIndexOf('#');
        if (fragment > 0) {
            url = url.substring(0, fragment);
        }
        int query = url.lastIndexOf('?');
        if (query > 0) {
            url = url.substring(0, query);
        }
        int filenamePos = url.lastIndexOf('/');
        String filename = filenamePos >= 0 ? url.substring(filenamePos + 1) : url;
        int dotPos = filename.lastIndexOf('.');
        if (dotPos < 0) {
            return "";
        }
        return filename.substring(dotPos + 1);
    }
}
