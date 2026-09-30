package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DesktopDatabase implements AutoCloseable {
    private final Connection connection;

    public DesktopDatabase(File databaseFile) throws SQLException {
        databaseFile.getParentFile().mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        connection.setAutoCommit(true);
        createTables();
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS feeds ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "download_url TEXT NOT NULL UNIQUE, "
                    + "title TEXT, link TEXT, description TEXT, author TEXT, "
                    + "language TEXT, image_url TEXT, last_modified TEXT, state INTEGER DEFAULT 0)");
            stmt.execute("CREATE TABLE IF NOT EXISTS feed_items ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE, "
                    + "item_identifier TEXT NOT NULL, "
                    + "title TEXT, description TEXT, link TEXT, pub_date INTEGER, "
                    + "image_url TEXT, state INTEGER DEFAULT 0, "
                    + "favorite INTEGER DEFAULT 0, transcript_url TEXT, transcript_type TEXT, "
                    + "UNIQUE(feed_id, item_identifier))");
            stmt.execute("CREATE TABLE IF NOT EXISTS feed_media ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "item_id INTEGER NOT NULL UNIQUE REFERENCES feed_items(id) ON DELETE CASCADE, "
                    + "download_url TEXT, local_file_url TEXT, cache_file_url TEXT, "
                    + "download_date INTEGER DEFAULT 0, "
                    + "duration INTEGER DEFAULT 0, position INTEGER DEFAULT 0, "
                    + "size INTEGER DEFAULT 0, mime_type TEXT, "
                    + "played_duration INTEGER DEFAULT 0, last_played_statistics INTEGER DEFAULT 0, "
                    + "last_played_history INTEGER DEFAULT 0)");
            stmt.execute("CREATE TABLE IF NOT EXISTS queue ("
                    + "item_id INTEGER PRIMARY KEY REFERENCES feed_items(id) ON DELETE CASCADE, "
                    + "sort_order INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sync_actions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "podcast TEXT NOT NULL, episode TEXT NOT NULL, guid TEXT, action TEXT NOT NULL, "
                    + "timestamp INTEGER, started INTEGER DEFAULT -1, position INTEGER DEFAULT -1, "
                    + "total INTEGER DEFAULT -1)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sync_subscriptions (url TEXT PRIMARY KEY)");
            stmt.execute("CREATE TABLE IF NOT EXISTS chapters ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "item_id INTEGER NOT NULL REFERENCES feed_items(id) ON DELETE CASCADE, "
                    + "start_ms INTEGER NOT NULL, title TEXT, link TEXT, image_url TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS feed_preferences ("
                    + "feed_id INTEGER PRIMARY KEY REFERENCES feeds(id) ON DELETE CASCADE, "
                    + "speed REAL DEFAULT 0, auto_download INTEGER DEFAULT -1, "
                    + "auto_delete INTEGER DEFAULT -1, include_filter TEXT DEFAULT '', "
                    + "exclude_filter TEXT DEFAULT '', min_duration INTEGER DEFAULT -1, "
                    + "sort_code TEXT DEFAULT 'newest')");
            // apart from feed_preferences, whose writers re-save every column they read
            stmt.execute("CREATE TABLE IF NOT EXISTS feed_credentials ("
                    + "feed_id INTEGER PRIMARY KEY REFERENCES feeds(id) ON DELETE CASCADE, "
                    + "username TEXT NOT NULL, password TEXT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS feed_tags ("
                    + "feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE, "
                    + "tag TEXT NOT NULL, PRIMARY KEY (feed_id, tag))");
        }
        // databases from before per-feed sort carry no sort_code column at all
        ensureColumn("feed_preferences", "sort_code", "TEXT DEFAULT 'newest'");
        ensureColumn("feed_items", "favorite", "INTEGER DEFAULT 0");
        ensureColumn("feed_media", "last_played_history", "INTEGER DEFAULT 0");
        ensureColumn("feed_items", "transcript_url", "TEXT");
        ensureColumn("feed_items", "transcript_type", "TEXT");
        ensureColumn("feed_items", "synced_position", "INTEGER DEFAULT -1");
        ensureColumn("feed_media", "cache_file_url", "TEXT");
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    /** Work run by {@link #inTransaction}. */
    public interface Work<T> {
        T run() throws Exception;
    }

    /**
     * Runs {@code work} as one transaction: all of it is stored or, if it throws, none of it.
     * Holding the lock throughout also keeps other threads' writes out of the middle of it, and
     * a feed's few hundred inserts commit once instead of one disk sync per row.
     */
    public synchronized <T> T inTransaction(Work<T> work) throws Exception {
        if (!connection.getAutoCommit()) {
            // already inside one: become part of it
            return work.run();
        }
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (Exception | Error e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private interface SqlWork {
        void run() throws SQLException;
    }

    /** {@link #inTransaction} for writers that only throw SQLException. */
    private synchronized void inSqlTransaction(SqlWork work) throws SQLException {
        try {
            inTransaction(() -> {
                work.run();
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public synchronized long insertFeed(Feed feed) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO feeds (download_url, title, link, description, author, language, image_url,"
                        + " last_modified, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, feed.getDownloadUrl());
            setNullable(stmt, 2, feed.getTitle());
            setNullable(stmt, 3, feed.getLink());
            setNullable(stmt, 4, feed.getDescription());
            setNullable(stmt, 5, feed.getAuthor());
            setNullable(stmt, 6, feed.getLanguage());
            setNullable(stmt, 7, feed.getImageUrl());
            setNullable(stmt, 8, feed.getLastModified());
            stmt.setInt(9, feed.getState());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                feed.setId(id);
                return id;
            }
        }
    }

    public synchronized void updateFeed(Feed feed) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feeds SET title = ?, link = ?, description = ?, author = ?, language = ?,"
                        + " image_url = ?, last_modified = ?, state = ? WHERE id = ?")) {
            setNullable(stmt, 1, feed.getTitle());
            setNullable(stmt, 2, feed.getLink());
            setNullable(stmt, 3, feed.getDescription());
            setNullable(stmt, 4, feed.getAuthor());
            setNullable(stmt, 5, feed.getLanguage());
            setNullable(stmt, 6, feed.getImageUrl());
            setNullable(stmt, 7, feed.getLastModified());
            stmt.setInt(8, feed.getState());
            stmt.setLong(9, feed.getId());
            stmt.executeUpdate();
        }
    }

    /** Removes one episode's rows (chapters, queue entry, media, item); no file is touched. */
    public synchronized void deleteItem(long itemId) throws SQLException {
        inSqlTransaction(() -> {
            for (String sql : new String[]{
                    "DELETE FROM chapters WHERE item_id = ?",
                    "DELETE FROM queue WHERE item_id = ?",
                    "DELETE FROM feed_media WHERE item_id = ?",
                    "DELETE FROM feed_items WHERE id = ?"}) {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setLong(1, itemId);
                    stmt.executeUpdate();
                }
            }
        });
    }

    public synchronized void deleteFeed(long feedId) throws SQLException {
        try (PreparedStatement chapters = connection.prepareStatement(
                "DELETE FROM chapters WHERE item_id IN (SELECT id FROM feed_items WHERE feed_id = ?)");
             PreparedStatement queue = connection.prepareStatement(
                "DELETE FROM queue WHERE item_id IN (SELECT id FROM feed_items WHERE feed_id = ?)");
             PreparedStatement media = connection.prepareStatement(
                "DELETE FROM feed_media WHERE item_id IN (SELECT id FROM feed_items WHERE feed_id = ?)");
             PreparedStatement items = connection.prepareStatement("DELETE FROM feed_items WHERE feed_id = ?");
             PreparedStatement prefs = connection.prepareStatement("DELETE FROM feed_preferences WHERE feed_id = ?");
             PreparedStatement credentials = connection.prepareStatement(
                "DELETE FROM feed_credentials WHERE feed_id = ?");
             PreparedStatement tags = connection.prepareStatement("DELETE FROM feed_tags WHERE feed_id = ?");
             PreparedStatement feed = connection.prepareStatement("DELETE FROM feeds WHERE id = ?")) {
            credentials.setLong(1, feedId);
            credentials.executeUpdate();
            tags.setLong(1, feedId);
            tags.executeUpdate();
            chapters.setLong(1, feedId);
            chapters.executeUpdate();
            queue.setLong(1, feedId);
            queue.executeUpdate();
            media.setLong(1, feedId);
            media.executeUpdate();
            items.setLong(1, feedId);
            items.executeUpdate();
            prefs.setLong(1, feedId);
            prefs.executeUpdate();
            feed.setLong(1, feedId);
            feed.executeUpdate();
        }
    }

    public synchronized List<Feed> getAllFeeds() throws SQLException {
        List<Feed> feeds = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")) {
            while (rs.next()) {
                feeds.add(readFeed(rs));
            }
        }
        return feeds;
    }

    public synchronized Map<Long, Long> getFeedLastPlayedTimes() throws SQLException {
        Map<Long, Long> times = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT i.feed_id AS feed_id,"
                             + " MAX(CASE WHEN COALESCE(m.last_played_statistics, 0)"
                             + " > COALESCE(m.last_played_history, 0)"
                             + " THEN m.last_played_statistics ELSE m.last_played_history END)"
                             + " AS last_played"
                             + " FROM feed_items i JOIN feed_media m ON m.item_id = i.id"
                             + " GROUP BY i.feed_id")) {
            while (rs.next()) {
                long value = rs.getLong("last_played");
                if (!rs.wasNull() && value > 0) {
                    times.put(rs.getLong("feed_id"), value);
                }
            }
        }
        return times;
    }

    public synchronized Feed getFeed(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM feeds WHERE id = ?")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Feed feed = readFeed(rs);
                feed.setItems(getItemsOfFeed(feedId));
                return feed;
            }
        }
    }

    public synchronized boolean feedExists(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM feeds WHERE id = ?")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized Feed getFeedByDownloadUrl(String downloadUrl) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM feeds WHERE download_url = ?")) {
            stmt.setString(1, downloadUrl);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Feed feed = readFeed(rs);
                feed.setItems(getItemsOfFeed(feed.getId()));
                return feed;
            }
        }
    }

    private Feed readFeed(ResultSet rs) throws SQLException {
        Feed feed = new Feed(rs.getString("download_url"), rs.getString("last_modified"));
        feed.setId(rs.getLong("id"));
        feed.setTitle(rs.getString("title"));
        feed.setLink(rs.getString("link"));
        feed.setDescription(rs.getString("description"));
        feed.setAuthor(rs.getString("author"));
        feed.setLanguage(rs.getString("language"));
        feed.setImageUrl(rs.getString("image_url"));
        feed.setState(rs.getInt("state"));
        return feed;
    }

    public synchronized long insertItem(long feedId, FeedItem item) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO feed_items (feed_id, item_identifier, title, description, link, pub_date,"
                        + " image_url, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, feedId);
            stmt.setString(2, item.getIdentifyingValue());
            setNullable(stmt, 3, item.getTitle());
            setNullable(stmt, 4, item.getDescription());
            setNullable(stmt, 5, item.getLink());
            if (item.getPubDate() != null) {
                stmt.setLong(6, item.getPubDate().getTime());
            } else {
                stmt.setNull(6, Types.BIGINT);
            }
            setNullable(stmt, 7, item.getImageUrl());
            stmt.setInt(8, item.getPlayState());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                item.setId(id);
                item.setFeedId(feedId);
                return id;
            }
        }
    }

    public synchronized void updateItem(FeedItem item) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_items SET title = ?, description = ?, link = ?, pub_date = ?,"
                        + " image_url = ?, state = ? WHERE id = ?")) {
            setNullable(stmt, 1, item.getTitle());
            setNullable(stmt, 2, item.getDescription());
            setNullable(stmt, 3, item.getLink());
            if (item.getPubDate() != null) {
                stmt.setLong(4, item.getPubDate().getTime());
            } else {
                stmt.setNull(4, Types.BIGINT);
            }
            setNullable(stmt, 5, item.getImageUrl());
            stmt.setInt(6, item.getPlayState());
            stmt.setLong(7, item.getId());
            stmt.executeUpdate();
        }
    }

    public synchronized void setItemState(long itemId, int state) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("UPDATE feed_items SET state = ? WHERE id = ?")) {
            stmt.setInt(1, state);
            stmt.setLong(2, itemId);
            stmt.executeUpdate();
        }
    }

    /**
     * Clears the NEW flag of a feed's unseen episodes, leaving played state alone.
     * A "seen" marker is local only, so unlike applyPlayedState it records no sync action.
     *
     * @return how many episodes were marked seen
     */
    public synchronized int clearNewFlags(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_items SET state = ? WHERE feed_id = ? AND state = ?")) {
            stmt.setInt(1, FeedItem.UNPLAYED);
            stmt.setLong(2, feedId);
            stmt.setInt(3, FeedItem.NEW);
            return stmt.executeUpdate();
        }
    }

    /**
     * Clears the NEW flag of every unseen episode in every feed, leaving played state alone.
     * A "seen" marker is local only, so unlike applyPlayedState it records no sync action.
     *
     * @return how many episodes were marked seen
     */
    public synchronized int clearAllNewFlags() throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_items SET state = ? WHERE state = ?")) {
            stmt.setInt(1, FeedItem.UNPLAYED);
            stmt.setInt(2, FeedItem.NEW);
            return stmt.executeUpdate();
        }
    }

    public synchronized List<FeedItem> getItemsOfFeed(long feedId) throws SQLException {
        List<FeedItem> items = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM feed_items WHERE feed_id = ? ORDER BY pub_date DESC NULLS LAST, id DESC")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    items.add(readItem(rs));
                }
            }
        }
        for (FeedItem item : items) {
            FeedMedia media = getMediaOfItem(item.getId());
            if (media != null) {
                media.setItem(item);
                item.setMedia(media);
            }
        }
        return items;
    }

    private FeedItem readItem(ResultSet rs) throws SQLException {
        FeedItem item = new FeedItem();
        item.setId(rs.getLong("id"));
        item.setFeedId(rs.getLong("feed_id"));
        item.setItemIdentifier(rs.getString("item_identifier"));
        item.setTitle(rs.getString("title"));
        item.setDescriptionIfLonger(rs.getString("description"));
        item.setLink(rs.getString("link"));
        long pubDate = rs.getLong("pub_date");
        if (!rs.wasNull()) {
            item.setPubDate(new Date(pubDate));
        }
        item.setImageUrl(rs.getString("image_url"));
        item.setPlayState(rs.getInt("state"));
        if (rs.getInt("favorite") > 0) {
            item.addTag(FeedItem.TAG_FAVORITE);
        }
        String transcriptUrl = rs.getString("transcript_url");
        String transcriptType = rs.getString("transcript_type");
        if (transcriptUrl != null && !transcriptUrl.isEmpty()) {
            item.setTranscriptUrl(transcriptType, transcriptUrl);
        }
        List<Chapter> chapters = getChapters(item.getId());
        if (!chapters.isEmpty()) {
            item.setChapters(chapters);
        }
        return item;
    }

    public synchronized void saveTranscriptInfo(long itemId, String type, String url) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_items SET transcript_url = ?, transcript_type = ? WHERE id = ?")) {
            if (url != null) {
                stmt.setString(1, url);
            } else {
                stmt.setNull(1, Types.VARCHAR);
            }
            if (type != null) {
                stmt.setString(2, type);
            } else {
                stmt.setNull(2, Types.VARCHAR);
            }
            stmt.setLong(3, itemId);
            stmt.executeUpdate();
        }
    }

    public synchronized void setFavorite(long itemId, boolean favorite) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_items SET favorite = ? WHERE id = ?")) {
            stmt.setInt(1, favorite ? 1 : 0);
            stmt.setLong(2, itemId);
            stmt.executeUpdate();
        }
    }

    public synchronized List<FeedItem> getFavorites() throws SQLException {
        List<FeedItem> items = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM feed_items WHERE favorite = 1 ORDER BY pub_date DESC NULLS LAST, id DESC")) {
            while (rs.next()) {
                items.add(readItem(rs));
            }
        }
        for (FeedItem item : items) {
            FeedMedia media = getMediaOfItem(item.getId());
            if (media != null) {
                media.setItem(item);
                item.setMedia(media);
            }
        }
        return items;
    }

    /** Every episode with a downloaded file, most recently downloaded first, with its media. */
    public synchronized List<FeedItem> getDownloadedItems() throws SQLException {
        List<FeedItem> items = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT fi.* FROM feed_items fi JOIN feed_media fm ON fm.item_id = fi.id"
                             + " JOIN feeds f ON f.id = fi.feed_id"
                             + " WHERE fm.local_file_url IS NOT NULL AND fm.download_date > 0"
                             // a local folder's files are the user's own, not downloads
                             + " AND f.download_url NOT LIKE 'antennapod_local:%'"
                             + " ORDER BY fm.download_date DESC, fi.id DESC")) {
            while (rs.next()) {
                items.add(readItem(rs));
            }
        }
        for (FeedItem item : items) {
            FeedMedia media = getMediaOfItem(item.getId());
            if (media != null) {
                media.setItem(item);
                item.setMedia(media);
            }
        }
        return items;
    }

    /**
     * Episodes of every subscription whose title or show notes contain the text, ignoring case:
     * title matches first, then newest first. Wildcards in the text match literally.
     */
    public synchronized List<FeedItem> searchItems(String text, int limit) throws SQLException {
        List<FeedItem> items = new ArrayList<>();
        String trimmed = text != null ? text.trim() : "";
        if (trimmed.isEmpty()) {
            return items;
        }
        String pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM feed_items WHERE title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\'"
                        + " ORDER BY (title LIKE ? ESCAPE '\\') DESC, pub_date DESC NULLS LAST, id DESC"
                        + " LIMIT ?")) {
            stmt.setString(1, pattern);
            stmt.setString(2, pattern);
            stmt.setString(3, pattern);
            stmt.setInt(4, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    items.add(readItem(rs));
                }
            }
        }
        for (FeedItem item : items) {
            FeedMedia media = getMediaOfItem(item.getId());
            if (media != null) {
                media.setItem(item);
                item.setMedia(media);
            }
        }
        return items;
    }

    /** Writes a consistent copy of the whole database to a file that must not exist yet. */
    public synchronized void snapshotTo(File target) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("VACUUM INTO ?")) {
            stmt.setString(1, target.getAbsolutePath());
            stmt.execute();
        }
    }

    /**
     * Forgets downloads whose file is not on this machine, e.g. after restoring a backup made on
     * another one; those episodes then stream or download again. Returns how many were cleared.
     */
    public synchronized int clearMissingDownloads() throws SQLException {
        List<Long> missing = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, local_file_url FROM feed_media WHERE local_file_url IS NOT NULL")) {
            while (rs.next()) {
                if (!new File(rs.getString("local_file_url")).isFile()) {
                    missing.add(rs.getLong("id"));
                }
            }
        }
        for (long mediaId : missing) {
            clearMediaDownload(mediaId);
        }
        return missing.size();
    }

    /** The episode a media row belongs to, with that media attached, or null if either is gone. */
    public synchronized FeedItem getItemOfMedia(long mediaId) throws SQLException {
        FeedMedia media = getMedia(mediaId);
        if (media == null) {
            return null;
        }
        return getItem(media.getItemId());
    }

    public synchronized long insertMedia(long itemId, FeedMedia media) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO feed_media (item_id, download_url, local_file_url, download_date, duration,"
                        + " position, size, mime_type, played_duration, last_played_statistics)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, itemId);
            setNullable(stmt, 2, media.getDownloadUrl());
            setNullable(stmt, 3, media.getLocalFileUrl());
            stmt.setLong(4, media.getDownloadDate());
            stmt.setInt(5, media.getDuration());
            stmt.setInt(6, media.getPosition());
            stmt.setLong(7, media.getSize());
            setNullable(stmt, 8, media.getMimeType());
            stmt.setInt(9, media.getPlayedDuration());
            stmt.setLong(10, media.getLastPlayedTimeStatistics());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                media.setId(id);
                media.setItemId(itemId);
                return id;
            }
        }
    }

    public synchronized void updateMedia(FeedMedia media) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET download_url = ?, local_file_url = ?, cache_file_url = ?,"
                        + " download_date = ?, duration = ?,"
                        + " position = ?, size = ?, mime_type = ?, played_duration = ?,"
                        + " last_played_statistics = ?, last_played_history = ? WHERE id = ?")) {
            setNullable(stmt, 1, media.getDownloadUrl());
            setNullable(stmt, 2, media.getLocalFileUrl());
            setNullable(stmt, 3, media.getCacheFileUrl());
            stmt.setLong(4, media.getDownloadDate());
            stmt.setInt(5, media.getDuration());
            stmt.setInt(6, media.getPosition());
            stmt.setLong(7, media.getSize());
            setNullable(stmt, 8, media.getMimeType());
            stmt.setInt(9, media.getPlayedDuration());
            stmt.setLong(10, media.getLastPlayedTimeStatistics());
            Date history = media.getLastPlayedTimeHistory();
            stmt.setLong(11, history != null ? history.getTime() : 0);
            stmt.setLong(12, media.getId());
            stmt.executeUpdate();
        }
    }

    // The writers below each own a few columns. updateMedia writes every column from whatever
    // instance the caller holds, so a download, the playback cache, a feed refresh and the player
    // - each holding its own copy loaded at a different time - would undo one another's changes.

    /** Records a finished download. Position, history and the playback cache are left alone. */
    public synchronized void setMediaDownloaded(long mediaId, String localFileUrl, long downloadDate,
            long size) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET local_file_url = ?, download_date = ?,"
                        + " size = CASE WHEN ? > 0 THEN ? ELSE size END WHERE id = ?")) {
            stmt.setString(1, localFileUrl);
            stmt.setLong(2, downloadDate);
            stmt.setLong(3, size);
            stmt.setLong(4, size);
            stmt.setLong(5, mediaId);
            stmt.executeUpdate();
        }
    }

    /** Forgets an episode's download once its file is gone. */
    public synchronized void clearMediaDownload(long mediaId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET local_file_url = NULL, download_date = 0 WHERE id = ?")) {
            stmt.setLong(1, mediaId);
            stmt.executeUpdate();
        }
    }

    /** Records where the playback cache keeps an episode, or null once the copy is dropped. */
    public synchronized void setMediaCacheFile(long mediaId, String cacheFileUrl) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET cache_file_url = ? WHERE id = ?")) {
            setNullable(stmt, 1, cacheFileUrl);
            stmt.setLong(2, mediaId);
            stmt.executeUpdate();
        }
    }

    /** Saves how far an episode has been listened to. Its files are left alone. */
    public synchronized void updatePlaybackState(FeedMedia media) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET duration = ?, position = ?, played_duration = ?,"
                        + " last_played_statistics = ?, last_played_history = ? WHERE id = ?")) {
            stmt.setInt(1, media.getDuration());
            stmt.setInt(2, media.getPosition());
            stmt.setInt(3, media.getPlayedDuration());
            stmt.setLong(4, media.getLastPlayedTimeStatistics());
            Date history = media.getLastPlayedTimeHistory();
            stmt.setLong(5, history != null ? history.getTime() : 0);
            stmt.setLong(6, media.getId());
            stmt.executeUpdate();
        }
    }

    /**
     * Takes over what a feed refresh says about an episode's enclosure. A duration already known is
     * kept, since the player measures it more precisely than the feed states it.
     */
    public synchronized void updateMediaFromFeed(FeedMedia media) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET download_url = ?, mime_type = COALESCE(?, mime_type),"
                        + " size = CASE WHEN ? > 0 THEN ? ELSE size END,"
                        + " duration = CASE WHEN duration <= 0 THEN ? ELSE duration END WHERE id = ?")) {
            setNullable(stmt, 1, media.getDownloadUrl());
            setNullable(stmt, 2, media.getMimeType());
            stmt.setLong(3, media.getSize());
            stmt.setLong(4, media.getSize());
            stmt.setInt(5, media.getDuration());
            stmt.setLong(6, media.getId());
            stmt.executeUpdate();
        }
    }

    public synchronized void addToPlaybackHistory(long mediaId, Date date) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_media SET last_played_history = ? WHERE id = ?")) {
            stmt.setLong(1, date.getTime());
            stmt.setLong(2, mediaId);
            stmt.executeUpdate();
        }
    }

    public synchronized void clearPlaybackHistory() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE feed_media SET last_played_history = 0");
        }
    }

    public synchronized List<FeedItem> getPlaybackHistory(int limit) throws SQLException {
        List<FeedItem> items = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT i.* FROM feed_items i JOIN feed_media m ON m.item_id = i.id"
                        + " WHERE m.last_played_history > 0 ORDER BY m.last_played_history DESC LIMIT ?")) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    items.add(readItem(rs));
                }
            }
        }
        for (FeedItem item : items) {
            FeedMedia media = getMediaOfItem(item.getId());
            if (media != null) {
                media.setItem(item);
                item.setMedia(media);
            }
        }
        return items;
    }

    public synchronized List<FeedStatistics> getFeedStatistics() throws SQLException {
        List<FeedStatistics> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT f.id, f.title,"
                             + " COUNT(i.id) AS episodes,"
                             + " SUM(CASE WHEN m.position > 0 OR i.state = 1 THEN 1 ELSE 0 END) AS started,"
                             + " COALESCE(SUM(m.played_duration), 0) AS played_time,"
                             + " COALESCE(SUM(m.duration), 0) AS total_time,"
                             + " SUM(CASE WHEN m.download_date > 0 THEN 1 ELSE 0 END) AS downloaded,"
                             + " COALESCE(SUM(CASE WHEN m.download_date > 0 THEN m.size ELSE 0 END), 0)"
                             + " AS download_size,"
                             + " SUM(CASE WHEN i.state != 1 THEN 1 ELSE 0 END) AS unplayed"
                             + " FROM feeds f LEFT JOIN feed_items i ON i.feed_id = f.id"
                             + " LEFT JOIN feed_media m ON m.item_id = i.id"
                             + " GROUP BY f.id, f.title ORDER BY f.title COLLATE NOCASE")) {
            while (rs.next()) {
                result.add(new FeedStatistics(rs.getLong("id"), rs.getString("title"),
                        rs.getInt("episodes"), rs.getInt("started"), rs.getLong("played_time"),
                        rs.getLong("total_time"), rs.getInt("downloaded"), rs.getLong("download_size"),
                        rs.getInt("unplayed")));
            }
        }
        return result;
    }

    public synchronized List<MonthlyStatistics> getMonthlyStatistics() throws SQLException {
        List<MonthlyStatistics> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT strftime('%Y-%m', datetime(last_played_history / 1000, 'unixepoch')) AS month,"
                             + " COALESCE(SUM(played_duration), 0) AS played_time"
                             + " FROM feed_media WHERE last_played_history > 0"
                             + " GROUP BY month ORDER BY month DESC LIMIT 24")) {
            while (rs.next()) {
                result.add(new MonthlyStatistics(rs.getString("month"), rs.getLong("played_time")));
            }
        }
        return result;
    }

    public static final class FeedStatistics {
        public final long feedId;
        public final String feedTitle;
        public final int episodes;
        public final int started;
        public final long playedTimeMs;
        public final long totalTimeMs;
        public final int downloaded;
        public final long downloadSizeBytes;
        public final int unplayed;

        FeedStatistics(long feedId, String feedTitle, int episodes, int started, long playedTimeMs,
                       long totalTimeMs, int downloaded, long downloadSizeBytes, int unplayed) {
            this.feedId = feedId;
            this.feedTitle = feedTitle;
            this.episodes = episodes;
            this.started = started;
            this.playedTimeMs = playedTimeMs;
            this.totalTimeMs = totalTimeMs;
            this.downloaded = downloaded;
            this.downloadSizeBytes = downloadSizeBytes;
            this.unplayed = unplayed;
        }
    }

    public static final class MonthlyStatistics {
        public final String month;
        public final long playedTimeMs;

        MonthlyStatistics(String month, long playedTimeMs) {
            this.month = month;
            this.playedTimeMs = playedTimeMs;
        }
    }

    public synchronized FeedMedia getMedia(long mediaId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM feed_media WHERE id = ?")) {
            stmt.setLong(1, mediaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return readMedia(rs);
            }
        }
    }

    public synchronized List<FeedMedia> getCachedMedia() throws SQLException {
        List<FeedMedia> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM feed_media WHERE cache_file_url IS NOT NULL")) {
            while (rs.next()) {
                result.add(readMedia(rs));
            }
        }
        return result;
    }

    public synchronized List<FeedMedia> getCachedFinishedMedia() throws SQLException {
        List<FeedMedia> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT m.* FROM feed_media m JOIN feed_items i ON i.id = m.item_id"
                        + " WHERE m.cache_file_url IS NOT NULL AND i.state = ?")) {
            stmt.setInt(1, FeedItem.PLAYED);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(readMedia(rs));
                }
            }
        }
        return result;
    }

    public synchronized FeedItem getItem(long itemId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM feed_items WHERE id = ?")) {
            stmt.setLong(1, itemId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                FeedItem item = readItem(rs);
                FeedMedia media = getMediaOfItem(itemId);
                if (media != null) {
                    media.setItem(item);
                    item.setMedia(media);
                }
                return item;
            }
        }
    }

    private FeedMedia getMediaOfItem(long itemId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM feed_media WHERE item_id = ?")) {
            stmt.setLong(1, itemId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return readMedia(rs);
            }
        }
    }

    private FeedMedia readMedia(ResultSet rs) throws SQLException {
        FeedMedia media = new FeedMedia(null, rs.getString("download_url"), rs.getLong("size"),
                rs.getString("mime_type"));
        media.setId(rs.getLong("id"));
        media.setItemId(rs.getLong("item_id"));
        media.setLocalFileUrl(rs.getString("local_file_url"));
        media.setCacheFileUrl(rs.getString("cache_file_url"));
        media.setDownloaded(rs.getLong("download_date") > 0, rs.getLong("download_date"));
        media.setDuration(rs.getInt("duration"));
        media.setPosition(rs.getInt("position"));
        media.setPlayedDuration(rs.getInt("played_duration"));
        media.setLastPlayedTimeStatistics(rs.getLong("last_played_statistics"));
        long history = rs.getLong("last_played_history");
        if (!rs.wasNull() && history > 0) {
            media.setLastPlayedTimeHistory(new Date(history));
        }
        return media;
    }

    public synchronized int getSyncedPosition(long itemId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT synced_position FROM feed_items WHERE id = ?")) {
            stmt.setLong(1, itemId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("synced_position") : -1;
            }
        }
    }

    public synchronized void setSyncedPosition(long itemId, int positionMs) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE feed_items SET synced_position = ? WHERE id = ?")) {
            stmt.setInt(1, positionMs);
            stmt.setLong(2, itemId);
            stmt.executeUpdate();
        }
    }

    /** Synced positions of a feed's episodes that have one, by item id. */
    public synchronized Map<Long, Integer> getSyncedPositions(long feedId) throws SQLException {
        Map<Long, Integer> positions = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, synced_position FROM feed_items WHERE feed_id = ? AND synced_position > 0")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    positions.put(rs.getLong(1), rs.getInt(2));
                }
            }
        }
        return positions;
    }

    /**
     * Unplayed and new episode counts of every feed in one query, as {unplayed, new} by feed id.
     * Feeds without episodes are absent.
     */
    public synchronized Map<Long, int[]> getFeedCounts() throws SQLException {
        Map<Long, int[]> counts = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT feed_id,"
                     + " SUM(CASE WHEN state != 1 THEN 1 ELSE 0 END),"
                     + " SUM(CASE WHEN state = -1 THEN 1 ELSE 0 END)"
                     + " FROM feed_items GROUP BY feed_id")) {
            while (rs.next()) {
                counts.put(rs.getLong(1), new int[] {rs.getInt(2), rs.getInt(3)});
            }
        }
        return counts;
    }

    public synchronized int countUnplayed(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM feed_items WHERE feed_id = ? AND state != 1")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public synchronized int countNew(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM feed_items WHERE feed_id = ? AND state = -1")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public synchronized void addToQueue(long itemId) throws SQLException {
        if (isInQueue(itemId)) {
            return;
        }
        try (PreparedStatement max = connection.prepareStatement("SELECT COALESCE(MAX(sort_order), 0) FROM queue");
             ResultSet rs = max.executeQuery()) {
            rs.next();
            long order = rs.getLong(1) + 1;
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO queue (item_id, sort_order) VALUES (?, ?)")) {
                stmt.setLong(1, itemId);
                stmt.setLong(2, order);
                stmt.executeUpdate();
            }
        }
    }

    public synchronized void removeFromQueue(long itemId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM queue WHERE item_id = ?")) {
            stmt.setLong(1, itemId);
            stmt.executeUpdate();
        }
    }

    public synchronized void clearQueue() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM queue");
        }
    }

    public synchronized boolean isInQueue(long itemId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM queue WHERE item_id = ?")) {
            stmt.setLong(1, itemId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized void moveQueueItem(long itemId, boolean up) throws SQLException {
        List<Long> ids = getQueueIds();
        int index = ids.indexOf(itemId);
        int swapWith = up ? index - 1 : index + 1;
        if (index < 0 || swapWith < 0 || swapWith >= ids.size()) {
            return;
        }
        long otherId = ids.get(swapWith);
        long orderOfItem = getQueueOrder(itemId);
        long orderOfOther = getQueueOrder(otherId);
        // both rows or neither: half a swap leaves two entries with the same place in the queue
        inSqlTransaction(() -> swapQueueOrder(itemId, orderOfOther, otherId, orderOfItem));
    }

    /**
     * Moves an episode to a place in the queue (0 is the top; past the end means last), as a
     * drag and drop does, and renumbers the whole queue in one transaction.
     */
    public synchronized void moveQueueItemTo(long itemId, int index) throws SQLException {
        List<Long> ids = getQueueIds();
        if (!ids.remove(itemId)) {
            return;
        }
        ids.add(Math.max(0, Math.min(index, ids.size())), itemId);
        inSqlTransaction(() -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE queue SET sort_order = ? WHERE item_id = ?")) {
                for (int i = 0; i < ids.size(); i++) {
                    stmt.setLong(1, i + 1);
                    stmt.setLong(2, ids.get(i));
                    stmt.executeUpdate();
                }
            }
        });
    }

    private void swapQueueOrder(long itemId, long orderOfOther, long otherId, long orderOfItem)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE queue SET sort_order = ? WHERE item_id = ?")) {
            stmt.setLong(1, orderOfOther);
            stmt.setLong(2, itemId);
            stmt.executeUpdate();
            stmt.setLong(1, orderOfItem);
            stmt.setLong(2, otherId);
            stmt.executeUpdate();
        }
    }

    private long getQueueOrder(long itemId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT sort_order FROM queue WHERE item_id = ?")) {
            stmt.setLong(1, itemId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<Long> getQueueIds() throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT item_id FROM queue ORDER BY sort_order")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    public synchronized List<FeedItem> getQueue() throws SQLException {
        List<FeedItem> queue = new ArrayList<>();
        for (long itemId : getQueueIds()) {
            FeedItem item = getItem(itemId);
            if (item != null) {
                queue.add(item);
            } else {
                removeFromQueue(itemId);
            }
        }
        return queue;
    }

    public synchronized void enqueueSyncAction(
            String podcast, String episode, String guid, String action,
            long timestamp, int started, int position, int total) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO sync_actions (podcast, episode, guid, action, timestamp,"
                        + " started, position, total) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, podcast);
            stmt.setString(2, episode);
            setNullable(stmt, 3, guid);
            stmt.setString(4, action);
            stmt.setLong(5, timestamp);
            stmt.setInt(6, started);
            stmt.setInt(7, position);
            stmt.setInt(8, total);
            stmt.executeUpdate();
        }
    }

    public synchronized List<SyncAction> getQueuedSyncActions() throws SQLException {
        List<SyncAction> actions = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sync_actions ORDER BY id")) {
            while (rs.next()) {
                actions.add(new SyncAction(rs.getLong("id"), rs.getString("podcast"),
                        rs.getString("episode"), rs.getString("guid"), rs.getString("action"),
                        rs.getLong("timestamp"), rs.getInt("started"), rs.getInt("position"),
                        rs.getInt("total")));
            }
        }
        return actions;
    }

    public synchronized void clearSyncActions() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM sync_actions");
        }
    }

    public synchronized void deleteSyncActions(List<Long> ids) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM sync_actions WHERE id = ?")) {
            for (long id : ids) {
                stmt.setLong(1, id);
                stmt.executeUpdate();
            }
        }
    }

    public synchronized String getSyncState(String key, String fallback) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT value FROM sync_state WHERE key = ?")) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        }
    }

    public synchronized void setSyncState(String key, String value) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO sync_state (key, value) VALUES (?, ?)"
                        + " ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.executeUpdate();
        }
    }

    public synchronized List<String> getSyncSubscriptionSnapshot() throws SQLException {
        List<String> urls = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT url FROM sync_subscriptions")) {
            while (rs.next()) {
                urls.add(rs.getString(1));
            }
        }
        return urls;
    }

    public synchronized void setSyncSubscriptionSnapshot(List<String> urls) throws SQLException {
        // one transaction: a crash between the DELETE and the INSERTs left a partial snapshot,
        // which the next sync read as subscriptions to upload or remove
        inSqlTransaction(() -> {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM sync_subscriptions");
            }
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO sync_subscriptions (url) VALUES (?)")) {
                for (String url : urls) {
                    stmt.setString(1, url);
                    stmt.executeUpdate();
                }
            }
        });
    }

    public synchronized FeedItem findItemByEpisodeUrl(String feedDownloadUrl, String mediaDownloadUrl, String guid)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                // In the named feed by enclosure or GUID; failing that, by enclosure in any feed:
                // the feed may be stored under another URL than the sender's (a redirect
                // followed when subscribing here). Never by GUID alone across feeds - short
                // GUIDs like "1" repeat between podcasts.
                "SELECT i.id FROM feed_items i JOIN feed_media m ON m.item_id = i.id"
                        + " JOIN feeds f ON f.id = i.feed_id"
                        + " WHERE (f.download_url = ? AND (m.download_url = ?"
                        + " OR i.item_identifier = ?)) OR m.download_url = ?"
                        + " ORDER BY (f.download_url = ?) DESC LIMIT 1")) {
            stmt.setString(1, feedDownloadUrl);
            stmt.setString(2, mediaDownloadUrl);
            stmt.setString(3, guid != null ? guid : mediaDownloadUrl);
            stmt.setString(4, mediaDownloadUrl);
            stmt.setString(5, feedDownloadUrl);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return getItem(rs.getLong(1));
            }
        }
    }

    public synchronized FeedPrefs getFeedPrefs(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM feed_preferences WHERE feed_id = ?")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                FeedPrefs prefs = new FeedPrefs(feedId);
                if (rs.next()) {
                    prefs.speed = rs.getFloat("speed");
                    prefs.autoDownload = rs.getInt("auto_download");
                    prefs.autoDelete = rs.getInt("auto_delete");
                    prefs.includeFilter = rs.getString("include_filter");
                    prefs.excludeFilter = rs.getString("exclude_filter");
                    prefs.minDurationSec = rs.getInt("min_duration");
                    String sortCode = rs.getString("sort_code");
                    prefs.sortCode = sortCode != null ? sortCode : "newest";
                }
                return prefs;
            }
        }
    }

    public synchronized void saveFeedPrefs(FeedPrefs prefs) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO feed_preferences (feed_id, speed, auto_download, auto_delete,"
                        + " include_filter, exclude_filter, min_duration, sort_code)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(feed_id) DO UPDATE SET speed = excluded.speed,"
                        + " auto_download = excluded.auto_download, auto_delete = excluded.auto_delete,"
                        + " include_filter = excluded.include_filter,"
                        + " exclude_filter = excluded.exclude_filter,"
                        + " min_duration = excluded.min_duration, sort_code = excluded.sort_code")) {
            stmt.setLong(1, prefs.feedId);
            stmt.setFloat(2, prefs.speed);
            stmt.setInt(3, prefs.autoDownload);
            stmt.setInt(4, prefs.autoDelete);
            stmt.setString(5, prefs.includeFilter);
            stmt.setString(6, prefs.excludeFilter);
            stmt.setInt(7, prefs.minDurationSec);
            stmt.setString(8, prefs.sortCode);
            stmt.executeUpdate();
        }
    }

    /** Every subscription's tags, by feed id; subscriptions without tags are absent. */
    public synchronized Map<Long, java.util.SortedSet<String>> getFeedTags() throws SQLException {
        Map<Long, java.util.SortedSet<String>> tags = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT feed_id, tag FROM feed_tags")) {
            while (rs.next()) {
                tags.computeIfAbsent(rs.getLong("feed_id"),
                        id -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(rs.getString("tag"));
            }
        }
        return tags;
    }

    /**
     * Replaces a subscription's tags. Tags are trimmed, blank ones dropped, and two spellings
     * differing only in case count as one (the first one given is kept).
     */
    public synchronized void setFeedTags(long feedId, java.util.Collection<String> tags) throws SQLException {
        java.util.SortedSet<String> clean = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String tag : tags) {
            String trimmed = tag != null ? tag.trim() : "";
            if (!trimmed.isEmpty()) {
                clean.add(trimmed);
            }
        }
        inSqlTransaction(() -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM feed_tags WHERE feed_id = ?")) {
                delete.setLong(1, feedId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO feed_tags (feed_id, tag) VALUES (?, ?)")) {
                for (String tag : clean) {
                    insert.setLong(1, feedId);
                    insert.setString(2, tag);
                    insert.executeUpdate();
                }
            }
        });
    }

    /** The username and password a feed is fetched with, or null when it has none. */
    public synchronized FeedCredentials.Login getFeedCredentials(long feedId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT username, password FROM feed_credentials WHERE feed_id = ?")) {
            stmt.setLong(1, feedId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                        ? new FeedCredentials.Login(rs.getString("username"), rs.getString("password"))
                        : null;
            }
        }
    }

    /** Stores a feed's login; null or an empty username removes it. */
    public synchronized void setFeedCredentials(long feedId, FeedCredentials.Login login)
            throws SQLException {
        if (login == null || login.username.isEmpty()) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM feed_credentials WHERE feed_id = ?")) {
                stmt.setLong(1, feedId);
                stmt.executeUpdate();
            }
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO feed_credentials (feed_id, username, password) VALUES (?, ?, ?)"
                        + " ON CONFLICT(feed_id) DO UPDATE SET username = excluded.username,"
                        + " password = excluded.password")) {
            stmt.setLong(1, feedId);
            stmt.setString(2, login.username);
            stmt.setString(3, login.password);
            stmt.executeUpdate();
        }
    }

    /**
     * Every stored login with the hosts it answers for: the feed's own and those of its episode
     * files, which protected feeds usually serve from the same server.
     */
    public synchronized Map<String, FeedCredentials.Login> getCredentialsByHost() throws SQLException {
        Map<String, FeedCredentials.Login> byHost = new java.util.LinkedHashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT c.feed_id, c.username, c.password, f.download_url FROM feed_credentials c"
                             + " JOIN feeds f ON f.id = c.feed_id")) {
            List<Long> feedIds = new ArrayList<>();
            List<FeedCredentials.Login> logins = new ArrayList<>();
            while (rs.next()) {
                FeedCredentials.Login login =
                        new FeedCredentials.Login(rs.getString("username"), rs.getString("password"));
                String host = FeedCredentials.hostOf(rs.getString("download_url"));
                if (host != null) {
                    byHost.put(host, login);
                }
                feedIds.add(rs.getLong("feed_id"));
                logins.add(login);
            }
            for (int i = 0; i < feedIds.size(); i++) {
                try (PreparedStatement media = connection.prepareStatement(
                        "SELECT DISTINCT fm.download_url FROM feed_media fm"
                                + " JOIN feed_items fi ON fi.id = fm.item_id WHERE fi.feed_id = ?")) {
                    media.setLong(1, feedIds.get(i));
                    try (ResultSet urls = media.executeQuery()) {
                        while (urls.next()) {
                            String host = FeedCredentials.hostOf(urls.getString(1));
                            if (host != null) {
                                byHost.putIfAbsent(host, logins.get(i));
                            }
                        }
                    }
                }
            }
        }
        return byHost;
    }

    public synchronized void saveChapters(long itemId, List<Chapter> chapters) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM chapters WHERE item_id = ?")) {
            delete.setLong(1, itemId);
            delete.executeUpdate();
        }
        if (chapters == null || chapters.isEmpty()) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO chapters (item_id, start_ms, title, link, image_url)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            for (Chapter chapter : chapters) {
                stmt.setLong(1, itemId);
                stmt.setLong(2, chapter.getStart());
                setNullable(stmt, 3, chapter.getTitle());
                setNullable(stmt, 4, chapter.getLink());
                setNullable(stmt, 5, chapter.getImageUrl());
                stmt.executeUpdate();
            }
        }
    }

    public synchronized List<Chapter> getChapters(long itemId) throws SQLException {
        List<Chapter> chapters = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM chapters WHERE item_id = ? ORDER BY start_ms")) {
            stmt.setLong(1, itemId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Chapter chapter = new Chapter(rs.getLong("start_ms"), rs.getString("title"),
                            rs.getString("link"), rs.getString("image_url"));
                    chapter.setId(rs.getLong("id"));
                    chapters.add(chapter);
                }
            }
        }
        return chapters;
    }

    public static final class SyncAction {
        public final long id;
        public final String podcast;
        public final String episode;
        public final String guid;
        public final String action;
        public final long timestamp;
        public final int started;
        public final int position;
        public final int total;

        SyncAction(long id, String podcast, String episode, String guid, String action,
                   long timestamp, int started, int position, int total) {
            this.id = id;
            this.podcast = podcast;
            this.episode = episode;
            this.guid = guid;
            this.action = action;
            this.timestamp = timestamp;
            this.started = started;
            this.position = position;
            this.total = total;
        }
    }

    private static void setNullable(PreparedStatement stmt, int index, String value) throws SQLException {
        if (value != null) {
            stmt.setString(index, value);
        } else {
            stmt.setNull(index, Types.VARCHAR);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
