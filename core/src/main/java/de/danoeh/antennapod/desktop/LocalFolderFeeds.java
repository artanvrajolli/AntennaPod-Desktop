package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Subscriptions made from a folder of the user's own audio files.
 *
 * <p>Such a feed is stored under {@link Feed#PREFIX_LOCAL_FOLDER} plus the folder's URI (so
 * {@link Feed#isLocalFeed()} knows it), and each file becomes an episode that is already
 * "downloaded": its local file is the user's file and its URL a {@code file:} URI.
 *
 * <p>The files belong to the user. {@link #isAppOwned} is the rule every delete in the app goes
 * through: only files inside the app's own download and cache folders may ever be deleted.
 */
public final class LocalFolderFeeds {
    /** What JavaFX can play; other formats would only fail when started. */
    static final String[] PLAYABLE = {".mp3", ".m4a", ".m4b", ".aac", ".wav", ".aif", ".aiff", ".mp4", ".m4v"};
    /** Pictures used as the podcast's cover when the folder has one. */
    static final String[] COVERS = {"cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg"};
    /** How deep a folder is scanned: the folder, and sub-folders such as seasons. */
    static final int MAX_DEPTH = 3;

    private LocalFolderFeeds() {
    }

    public static String feedUrlFor(File folder) {
        return Feed.PREFIX_LOCAL_FOLDER + folder.getAbsoluteFile().toURI();
    }

    /** The folder of a local feed, or null for any other feed. */
    public static File folderOf(Feed feed) {
        String url = feed != null ? feed.getDownloadUrl() : null;
        if (url == null || !url.startsWith(Feed.PREFIX_LOCAL_FOLDER)) {
            return null;
        }
        try {
            return new File(URI.create(url.substring(Feed.PREFIX_LOCAL_FOLDER.length())));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isLocalFeedUrl(String url) {
        return url != null && url.startsWith(Feed.PREFIX_LOCAL_FOLDER);
    }

    /** Whether an episode is one of the user's files, from its URL alone (no database). */
    public static boolean isLocalMedia(FeedMedia media) {
        String url = media != null ? media.getDownloadUrl() : null;
        return url != null && url.regionMatches(true, 0, "file:", 0, 5);
    }

    /**
     * Whether the app may delete a file: only downloads and cached copies it made itself, which
     * live in its media and cache folders. Anything else (a local folder's files above all) is
     * the user's, and a null or unresolvable path is never deletable.
     */
    public static boolean isAppOwned(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            File file = new File(path).getCanonicalFile();
            return isInside(file, DesktopPreferences.getMediaDir())
                    || isInside(file, DesktopPreferences.getCacheDir());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isInside(File file, File dir) throws IOException {
        Path root = dir.getCanonicalFile().toPath();
        return file.toPath().startsWith(root) && !file.toPath().equals(root);
    }

    static boolean isPlayable(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String extension : PLAYABLE) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** The playable files under a folder, in path order. */
    static List<File> scan(File folder) throws IOException {
        List<File> files = new ArrayList<>();
        if (!folder.isDirectory()) {
            throw new IOException("The folder is gone: " + folder);
        }
        try (Stream<Path> paths = Files.walk(folder.toPath(), MAX_DEPTH)) {
            paths.map(Path::toFile).filter(File::isFile).filter(LocalFolderFeeds::isPlayable)
                    .sorted().forEach(files::add);
        }
        return files;
    }

    /** A feed with one episode per playable file, as a freshly parsed feed would be. */
    static Feed read(File folder) throws IOException {
        Feed feed = new Feed(feedUrlFor(folder), null, folder.getName());
        feed.setDescription("Your audio files in " + folder.getAbsolutePath());
        for (String cover : COVERS) {
            File image = new File(folder, cover);
            if (image.isFile()) {
                feed.setImageUrl(image.toURI().toString());
                break;
            }
        }
        List<FeedItem> items = new ArrayList<>();
        Path root = folder.toPath();
        for (File file : scan(folder)) {
            FeedItem item = new FeedItem();
            String relative = root.relativize(file.toPath()).toString().replace('\\', '/');
            item.setItemIdentifier(relative);
            String name = file.getName();
            item.setTitle(name.substring(0, name.lastIndexOf('.')));
            item.setPubDate(new Date(file.lastModified()));
            item.setFeed(feed);
            FeedMedia media = new FeedMedia(item, file.toURI().toString(), file.length(), mimeOf(name));
            media.setLocalFileUrl(file.getAbsolutePath());
            media.setDownloaded(true, Math.max(1, file.lastModified()));
            item.setMedia(media);
            items.add(item);
        }
        // newest first, as feeds list their episodes
        items.sort((a, b) -> b.getPubDate().compareTo(a.getPubDate()));
        feed.setItems(items);
        return feed;
    }

    static String mimeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        } else if (lower.endsWith(".m4a") || lower.endsWith(".m4b") || lower.endsWith(".aac")) {
            return "audio/mp4";
        } else if (lower.endsWith(".wav")) {
            return "audio/wav";
        } else if (lower.endsWith(".aif") || lower.endsWith(".aiff")) {
            return "audio/aiff";
        }
        return "video/mp4";
    }
}
