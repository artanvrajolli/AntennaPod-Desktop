package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.BooleanSupplier;
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
        return fetch(url, dir, mediaId, () -> true);
    }

    /**
     * As above; {@code current} says whether the episode is still the one playing once the
     * download is done. Fetches run concurrently, so only a current one may clear out the other
     * episodes' artwork - a slow, stale fetch would otherwise delete the file the card is about to
     * read for the episode that replaced it.
     */
    static File fetch(String url, File dir, long mediaId, BooleanSupplier current) {
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
            // a part file of its own: switching A -> B -> A quickly runs two fetches for A
            File part = File.createTempFile(mediaId + "-", PART_SUFFIX, dir);
            try {
                Files.write(part.toPath(), bytes);
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(part.toPath());
            }
            if (current.getAsBoolean()) {
                dropSiblings(dir, target);
            }
            return target;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final String PART_SUFFIX = ".part";
    /** A part file this old is left over from a crash, not a download still being written. */
    private static final long STALE_PART_MS = 60_000;

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
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.getName().endsWith(PART_SUFFIX) && now - file.lastModified() < STALE_PART_MS) {
                // another fetch is writing it right now
                continue;
            }
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
