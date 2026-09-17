package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.net.common.RedirectChecker;
import de.danoeh.antennapod.net.common.UrlChecker;
import de.danoeh.antennapod.net.discovery.PodcastSearcherRegistry;
import de.danoeh.antennapod.parser.feed.FeedHandler;
import de.danoeh.antennapod.parser.feed.FeedHandlerResult;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;

public final class FeedUpdater {
    private final DesktopDatabase database;

    public FeedUpdater(DesktopDatabase database) {
        this.database = database;
        DesktopHttp.init();
    }

    public Feed subscribe(String url) throws Exception {
        String prepared = UrlChecker.prepareUrl(url);
        if (PodcastSearcherRegistry.urlNeedsLookup(prepared)) {
            prepared = PodcastSearcherRegistry.lookupUrl(prepared).blockingGet();
        }
        String finalUrl = RedirectChecker.getFinalUrl(prepared);
        Feed existing = database.getFeedByDownloadUrl(prepared);
        if (existing == null && !finalUrl.equals(prepared)) {
            existing = database.getFeedByDownloadUrl(finalUrl);
        }
        if (existing != null) {
            return existing;
        }
        Feed downloaded = downloadAndParse(finalUrl);
        downloaded.setDownloadUrl(finalUrl);
        database.insertFeed(downloaded);
        for (FeedItem item : downloaded.getItems()) {
            item.setFeedId(downloaded.getId());
            item.setFeed(downloaded);
            item.setNew();
            long itemId = database.insertItem(downloaded.getId(), item);
            if (item.getMedia() != null) {
                item.getMedia().setItemId(itemId);
                database.insertMedia(itemId, item.getMedia());
            }
            if (item.getChapters() != null && !item.getChapters().isEmpty()) {
                database.saveChapters(itemId, item.getChapters());
            }
            persistTranscriptInfo(itemId, item);
        }
        return database.getFeed(downloaded.getId());
    }

    public List<FeedItem> refresh(Feed feed) throws Exception {
        Feed downloaded = downloadAndParse(feed.getDownloadUrl());
        feed.setTitle(downloaded.getTitle());
        feed.setLink(downloaded.getLink());
        feed.setDescription(downloaded.getDescription());
        feed.setAuthor(downloaded.getAuthor());
        feed.setLanguage(downloaded.getLanguage());
        if (downloaded.getImageUrl() != null) {
            feed.setImageUrl(downloaded.getImageUrl());
        }
        database.updateFeed(feed);

        Map<String, FeedItem> knownItems = new HashMap<>();
        for (FeedItem known : database.getItemsOfFeed(feed.getId())) {
            knownItems.put(known.getIdentifyingValue(), known);
        }
        List<FeedItem> newItems = new ArrayList<>();
        for (FeedItem parsed : downloaded.getItems()) {
            FeedItem known = knownItems.get(parsed.getIdentifyingValue());
            if (known == null) {
                parsed.setFeedId(feed.getId());
                parsed.setFeed(feed);
                parsed.setNew();
                long itemId = database.insertItem(feed.getId(), parsed);
                if (parsed.getMedia() != null) {
                    parsed.getMedia().setItemId(itemId);
                    database.insertMedia(itemId, parsed.getMedia());
                }
                newItems.add(parsed);
            } else {
                known.updateFromOther(parsed);
                database.updateItem(known);
                if (known.getMedia() != null) {
                    database.updateMedia(known.getMedia());
                } else if (parsed.getMedia() != null) {
                    parsed.getMedia().setItemId(known.getId());
                    database.insertMedia(known.getId(), parsed.getMedia());
                }
                if (known.getChapters() != null && !known.getChapters().isEmpty()) {
                    database.saveChapters(known.getId(), known.getChapters());
                }
                persistTranscriptInfo(known.getId(), known);
            }
        }
        return newItems;
    }

    public List<RefreshResult> refreshAll() throws Exception {
        List<RefreshResult> results = new ArrayList<>();
        for (Feed feed : database.getAllFeeds()) {
            try {
                List<FeedItem> added = refresh(feed);
                results.add(new RefreshResult(feed, added, null));
            } catch (Exception e) {
                results.add(new RefreshResult(feed, new ArrayList<>(), e));
            }
        }
        return results;
    }

    private void persistTranscriptInfo(long itemId, FeedItem item) {
        try {
            if (item.getTranscriptUrl() != null && !item.getTranscriptUrl().isEmpty()) {
                database.saveTranscriptInfo(itemId, item.getTranscriptType(), item.getTranscriptUrl());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Feed downloadAndParse(String url) throws Exception {
        DesktopPreferences.getCacheDir().mkdirs();
        File tempFile = File.createTempFile("antennapod-feed", ".xml", DesktopPreferences.getCacheDir());
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Feed download failed: " + response);
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("Empty feed response");
                }
                try (BufferedSink sink = Okio.buffer(Okio.sink(tempFile))) {
                    sink.writeAll(body.source());
                }
            }
            Feed feed = new Feed(url, null);
            feed.setLocalFileUrl(tempFile.getAbsolutePath());
            FeedHandlerResult result = new FeedHandler().parseFeed(feed);
            return result.feed;
        } finally {
            tempFile.delete();
        }
    }

    public static final class RefreshResult {
        public final Feed feed;
        public final List<FeedItem> newEpisodes;
        public final Exception error;

        RefreshResult(Feed feed, List<FeedItem> newEpisodes, Exception error) {
            this.feed = feed;
            this.newEpisodes = newEpisodes;
            this.error = error;
        }
    }
}
