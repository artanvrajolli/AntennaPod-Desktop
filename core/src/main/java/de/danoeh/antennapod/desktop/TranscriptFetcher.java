package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.parser.transcript.TranscriptParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        File cacheFile = cacheFile(item.getId());
        String text;
        if (cacheFile.exists()) {
            text = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
        } else {
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
                text = body.string();
            }
            cacheFile.getParentFile().mkdirs();
            Files.writeString(cacheFile.toPath(), text, StandardCharsets.UTF_8);
        }
        Transcript transcript = TranscriptParser.parse(text, item.getTranscriptType());
        if (transcript == null) {
            throw new IOException("Could not parse transcript (" + item.getTranscriptType() + ")");
        }
        return transcript;
    }

    public static void clearCache(long itemId) {
        cacheFile(itemId).delete();
    }

    private static File cacheFile(long itemId) {
        return new File(new File(DesktopPreferences.getCacheDir(), "transcripts"), itemId + ".txt");
    }
}
