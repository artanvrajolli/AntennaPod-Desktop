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

    /**
     * Downloads and parses a feed without storing any of it, so a podcast's details can be shown
     * before the user decides whether to subscribe.
     */
    public Feed preview(String url) throws Exception {
        String finalUrl = RedirectChecker.getFinalUrl(prepareAndLookup(url));
        Feed feed = downloadAndParse(finalUrl);
        feed.setDownloadUrl(finalUrl);
        return feed;
    }

    /** Turns what the user typed, or a directory handed us, into the URL of an actual feed. */
    private String prepareAndLookup(String url) throws Exception {
        String prepared = UrlChecker.prepareUrl(url);
        if (PodcastSearcherRegistry.urlNeedsLookup(prepared)) {
            prepared = PodcastSearcherRegistry.lookupUrl(prepared).blockingGet();
        }
        return prepared;
    }

    public Feed subscribe(String url) throws Exception {
        return subscribe(url, false, null);
    }

    /**
     * Subscribes to a password-protected feed. The login may also come written into the URL
     * ({@code https://user:pass@host/feed}); either way it is stored for the feed, never in its URL.
     */
    public Feed subscribe(String url, FeedCredentials.Login login) throws Exception {
        return subscribe(url, false, login);
    }

    /** Thrown when a feed answers 401: it needs a username and password. */
    public static final class AuthRequiredException extends IOException {
        public AuthRequiredException(String url) {
            super("This feed needs a username and password: " + url);
        }
    }

    /** Whether a failure (or anything it wraps) is a feed asking for a login. */
    public static boolean isAuthRequired(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof AuthRequiredException) {
                return true;
            }
        }
        return false;
    }

    /** Re-reads every stored login into the HTTP stacks, e.g. at startup or after a change. */
    public void reloadCredentials() throws Exception {
        FeedCredentials.install();
        FeedCredentials.setAll(database.getCredentialsByHost());
    }

    /** Stores (or with null, removes) a feed's login and makes it effective right away. */
    public void setCredentials(long feedId, FeedCredentials.Login login) throws Exception {
        database.setFeedCredentials(feedId, login);
        reloadCredentials();
    }

    /**
     * Subscribes under exactly the URL given, even when it redirects: for subscriptions that come
     * from a sync server, which knows the feed by that URL. Stored under the redirect's target
     * instead, the feed was never matched to the server's copy again - its removal on another
     * device did nothing here, its episode actions found no feed, and every sync uploaded the
     * target URL as a new subscription and offered the original one again.
     */
    public Feed subscribeKeepingUrl(String url) throws Exception {
        return subscribe(url, true, null);
    }

    private Feed subscribe(String url, boolean keepGivenUrl, FeedCredentials.Login login)
            throws Exception {
        FeedCredentials.Login inUrl = FeedCredentials.fromUserInfo(UrlChecker.prepareUrl(url));
        if (login == null) {
            login = inUrl;
        }
        String prepared = prepareAndLookup(FeedCredentials.withoutUserInfo(UrlChecker.prepareUrl(url)));
        if (login != null) {
            // effective for this fetch already; stored for the feed once it exists
            FeedCredentials.install();
            FeedCredentials.put(FeedCredentials.hostOf(prepared), login);
        }
        String finalUrl = RedirectChecker.getFinalUrl(prepared);
        if (login != null) {
            FeedCredentials.put(FeedCredentials.hostOf(finalUrl), login);
        }
        Feed existing = database.getFeedByDownloadUrl(prepared);
        if (existing == null && !finalUrl.equals(prepared)) {
            existing = database.getFeedByDownloadUrl(finalUrl);
        }
        if (existing != null) {
            if (login != null) {
                setCredentials(existing.getId(), login);
            }
            return existing;
        }
        Feed downloaded = downloadAndParse(finalUrl);
        downloaded.setDownloadUrl(keepGivenUrl ? prepared : finalUrl);
        // all or nothing: a failure halfway used to leave the feed with part of its episodes,
        // and the next attempt found it "already subscribed" and kept it that way
        // NEW means "arrived after the subscription was stored". The back catalogue
        // of a fresh subscription is not news, so subscribe stores UNPLAYED;
        // only refresh() flags arrivals as NEW.
        FeedCredentials.Login feedLogin = login;
        database.inTransaction(() -> {
            database.insertFeed(downloaded);
            if (feedLogin != null) {
                database.setFeedCredentials(downloaded.getId(), feedLogin);
            }
            for (FeedItem item : distinctItems(downloaded.getItems())) {
                item.setFeedId(downloaded.getId());
                item.setFeed(downloaded);
                item.setPlayState(FeedItem.UNPLAYED);
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
            return null;
        });
        if (feedLogin != null) {
            // now that its episodes are stored, their hosts answer with the login too
            reloadCredentials();
        }
        return database.getFeed(downloaded.getId());
    }

    /**
     * The parsed items that can be stored: one per identifier, and none without one. Feeds that
     * repeat a GUID (or a title, when they have no GUIDs) broke the unique index on insert, which
     * failed the whole subscribe or refresh. The first occurrence wins, as feeds list newest first.
     */
    static List<FeedItem> distinctItems(List<FeedItem> items) {
        List<FeedItem> distinct = new ArrayList<>();
        if (items == null) {
            return distinct;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FeedItem item : items) {
            String id = item.getIdentifyingValue();
            if (id != null && !id.isEmpty() && seen.add(id)) {
                distinct.add(item);
            }
        }
        return distinct;
    }

    /**
     * Removes a subscription together with its downloads and cached copies. Used both when the
     * user unsubscribes and when sync reports the feed removed on another device, which used to
     * drop only the database rows and leave the files on disk with nothing pointing at them.
     */
    public void unsubscribe(long feedId) throws Exception {
        for (FeedItem item : database.getItemsOfFeed(feedId)) {
            FeedMedia media = item.getMedia();
            if (media == null) {
                continue;
            }
            // only what the app downloaded or cached itself: a local folder's files are the user's
            if (LocalFolderFeeds.isAppOwned(media.getLocalFileUrl())) {
                new File(media.getLocalFileUrl()).delete();
            }
            if (LocalFolderFeeds.isAppOwned(media.getCacheFileUrl())) {
                new File(media.getCacheFileUrl()).delete();
            }
        }
        database.inTransaction(() -> {
            database.deleteFeed(feedId);
            return null;
        });
        // the per-feed folders are empty now unless something else lives there
        new File(DesktopPreferences.getMediaDir(), String.valueOf(feedId)).delete();
        new File(DesktopPreferences.getEpisodeCacheDir(), String.valueOf(feedId)).delete();
    }

    /**
     * Subscribes to a folder of audio files: each playable file is an episode that plays from
     * where it is. Subscribing again to the same folder returns the existing subscription.
     */
    public Feed subscribeLocalFolder(File folder) throws Exception {
        Feed existing = database.getFeedByDownloadUrl(LocalFolderFeeds.feedUrlFor(folder));
        if (existing != null) {
            return existing;
        }
        Feed scanned = LocalFolderFeeds.read(folder);
        database.inTransaction(() -> {
            database.insertFeed(scanned);
            for (FeedItem item : distinctItems(scanned.getItems())) {
                item.setFeedId(scanned.getId());
                item.setPlayState(FeedItem.UNPLAYED);
                long itemId = database.insertItem(scanned.getId(), item);
                item.getMedia().setItemId(itemId);
                database.insertMedia(itemId, item.getMedia());
            }
            return null;
        });
        return database.getFeed(scanned.getId());
    }

    /**
     * A local folder's refresh: files added since are new episodes, and episodes whose file is
     * gone leave the list (their rows only; nothing on disk is touched).
     */
    private List<FeedItem> rescanLocalFolder(Feed feed) throws Exception {
        File folder = LocalFolderFeeds.folderOf(feed);
        if (folder == null) {
            return new ArrayList<>();
        }
        Feed scanned = LocalFolderFeeds.read(folder);
        return database.inTransaction(() -> {
            if (!database.feedExists(feed.getId())) {
                return new ArrayList<FeedItem>();
            }
            Map<String, FeedItem> known = new HashMap<>();
            for (FeedItem item : database.getItemsOfFeed(feed.getId())) {
                known.put(item.getIdentifyingValue(), item);
            }
            List<FeedItem> added = new ArrayList<>();
            for (FeedItem item : distinctItems(scanned.getItems())) {
                if (known.remove(item.getIdentifyingValue()) != null) {
                    continue;
                }
                item.setFeedId(feed.getId());
                item.setFeed(feed);
                item.setNew();
                long itemId = database.insertItem(feed.getId(), item);
                item.getMedia().setItemId(itemId);
                database.insertMedia(itemId, item.getMedia());
                added.add(item);
            }
            for (FeedItem gone : known.values()) {
                database.deleteItem(gone.getId());
            }
            if (scanned.getImageUrl() != null && !scanned.getImageUrl().equals(feed.getImageUrl())) {
                feed.setImageUrl(scanned.getImageUrl());
                database.updateFeed(feed);
            }
            return added;
        });
    }

    public List<FeedItem> refresh(Feed feed) throws Exception {
        if (feed.isLocalFeed()) {
            return rescanLocalFolder(feed);
        }
        Feed downloaded = downloadAndParse(feed.getDownloadUrl());
        // the download is done outside the transaction; only storing it holds the database
        return database.inTransaction(() -> store(feed, downloaded));
    }

    private List<FeedItem> store(Feed feed, Feed downloaded) throws Exception {
        if (!database.feedExists(feed.getId())) {
            // unsubscribed while the feed was downloading: storing it now would bring back the
            // episodes as rows without a feed, and auto-download could fetch the whole catalogue
            return new ArrayList<>();
        }
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
        for (FeedItem parsed : distinctItems(downloaded.getItems())) {
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
                // as subscribe does for the back catalogue: a new episode played before the next
                // refresh would otherwise have no chapters and no transcript
                if (parsed.getChapters() != null && !parsed.getChapters().isEmpty()) {
                    database.saveChapters(itemId, parsed.getChapters());
                }
                persistTranscriptInfo(itemId, parsed);
                newItems.add(parsed);
            } else {
                known.updateFromOther(parsed);
                database.updateItem(known);
                FeedMedia media = known.getMedia();
                if (media != null && media.getId() > 0) {
                    database.updateMediaFromFeed(media);
                } else if (media != null) {
                    // updateFromOther adopts the parsed enclosure when the episode had none yet
                    media.setItemId(known.getId());
                    database.insertMedia(known.getId(), media);
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
                if (response.code() == 401) {
                    throw new AuthRequiredException(url);
                }
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
