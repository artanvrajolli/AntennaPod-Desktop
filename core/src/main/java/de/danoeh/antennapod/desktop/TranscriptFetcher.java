package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.parser.transcript.TranscriptParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class TranscriptFetcher {
    private TranscriptFetcher() {
    }

    public static Transcript fetch(FeedItem item) throws IOException {
        String url = item.getTranscriptUrl();
        if (url == null || url.isEmpty()) {
            throw new IOException("Episode has no transcript URL");
        }
        File cacheFile = cacheFile(item.getId(), url);
        if (cacheFile.exists()) {
            Transcript cached = TranscriptParser.parse(
                    Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8), item.getTranscriptType());
            if (cached != null) {
                return cached;
            }
            // a copy that no longer parses is thrown away and fetched again, not served forever
            cacheFile.delete();
        }
        String text = download(url);
        Transcript transcript = TranscriptParser.parse(text, item.getTranscriptType());
        if (transcript == null) {
            // not cached: an error page served with 200 would otherwise fail this episode for good
            throw new IOException("Could not parse transcript (" + item.getTranscriptType() + ")");
        }
        store(cacheFile, text);
        return transcript;
    }

    private static String download(String url) throws IOException {
        DesktopHttp.init();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Transcript download failed: " + response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty transcript response");
            }
            return body.string();
        }
    }

    /** Written aside and moved into place, so an interrupted write never looks like a cached copy. */
    private static void store(File cacheFile, String text) {
        try {
            cacheFile.getParentFile().mkdirs();
            File part = new File(cacheFile.getAbsolutePath() + ".part");
            Files.writeString(part.toPath(), text, StandardCharsets.UTF_8);
            Files.move(part.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // caching is an optimisation; the transcript itself was fetched fine
        }
    }

    public static void clearCache(long itemId) {
        File[] cached = cacheDir().listFiles((dir, name) -> name.startsWith(itemId + "-")
                || name.equals(itemId + ".txt"));
        if (cached != null) {
            for (File file : cached) {
                file.delete();
            }
        }
    }

    private static File cacheDir() {
        return new File(DesktopPreferences.getCacheDir(), "transcripts");
    }

    /** Keyed by the URL too, so a transcript the feed moves to a new address is fetched again. */
    static File cacheFile(long itemId, String url) {
        return new File(cacheDir(), itemId + "-" + Integer.toHexString(url.hashCode()) + ".txt");
    }
}
