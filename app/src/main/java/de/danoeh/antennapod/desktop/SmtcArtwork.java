package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Episode artwork as a file the system media card can read. The shell loads the thumbnail through
 * a StorageFile, so the remote image URL the UI shows has to be downloaded first.
 *
 * <p>One file per episode, reused while it is current; anything that fails just means no artwork
 * on the card, never a failed update. Callers run this off the UI thread.
 */
final class SmtcArtwork {
    /** Artwork bigger than this is not worth the download for a thumbnail. */
    static final long MAX_BYTES = 8L * 1024 * 1024;

    private SmtcArtwork() {
    }

    /**
     * The artwork for {@code mediaId} in {@code dir}, downloading it when it is not there yet.
     * Returns null when there is nothing to show.
     */
    static File fetch(String url, File dir, long mediaId) {
        if (url == null || url.isEmpty() || mediaId <= 0) {
            return null;
        }
        try {
            dir.mkdirs();
            if (!dir.isDirectory()) {
                return null;
            }
            File target = new File(dir, mediaId + extensionOf(url));
            if (target.isFile() && target.length() > 0) {
                return target;
            }
            byte[] bytes = download(url);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            File part = new File(dir, mediaId + ".part");
            Files.write(part.toPath(), bytes);
            Files.deleteIfExists(target.toPath());
            Files.move(part.toPath(), target.toPath());
            dropSiblings(dir, target);
            return target;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Drops artwork left over from earlier episodes, so the folder never grows. Best effort. */
    static void dropSiblings(File dir, File keep) {
        File[] files;
        try {
            files = dir.listFiles();
        } catch (RuntimeException e) {
            return;
        }
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.equals(keep)) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static byte[] download(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_BYTES) {
                return null;
            }
            byte[] bytes = body.bytes();
            return bytes.length > MAX_BYTES ? null : bytes;
        }
    }

    /** Image suffix from the URL path, so the shell sniffs the right kind. */
    static String extensionOf(String url) {
        String path = url.split("\\?", 2)[0].toLowerCase(Locale.US);
        for (String suffix : new String[]{".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"}) {
            if (path.endsWith(suffix)) {
                return suffix.equals(".jpeg") ? ".jpg" : suffix;
            }
        }
        return ".img";
    }
}
