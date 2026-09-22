package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Which copy of an episode the player opens. A download removed outside the app (Explorer, a
 * cleanup tool) is still recorded as present, and opening that path failed every time instead of
 * falling back to another copy or the stream.
 */
public class PlaybackSourceTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static FeedMedia media() {
        return new FeedMedia(null, "https://example.com/ep.mp3", 0, "audio/mpeg");
    }

    private static void markDownloaded(FeedMedia media, File file) {
        media.setLocalFileUrl(file.getAbsolutePath());
        media.setDownloaded(true, 1000L);
    }

    @Test
    public void testPlaysTheDownloadWhenItsFileExists() throws Exception {
        File download = tempFolder.newFile("download.mp3");
        FeedMedia media = media();
        markDownloaded(media, download);

        assertEquals(download.getAbsolutePath(), PlaybackManager.existingLocalCopy(media));
    }

    @Test
    public void testFallsBackToTheCacheWhenTheDownloadIsGone() throws Exception {
        File cached = tempFolder.newFile("cached.mp3");
        FeedMedia media = media();
        markDownloaded(media, new File(tempFolder.getRoot(), "deleted.mp3"));
        media.setCacheFileUrl(cached.getAbsolutePath());

        assertEquals(cached.getAbsolutePath(), PlaybackManager.existingLocalCopy(media));
    }

    @Test
    public void testStreamsWhenNoCopyExists() {
        FeedMedia media = media();
        markDownloaded(media, new File(tempFolder.getRoot(), "deleted.mp3"));
        media.setCacheFileUrl(new File(tempFolder.getRoot(), "evicted.mp3").getAbsolutePath());

        assertNull(PlaybackManager.existingLocalCopy(media));
    }

    @Test
    public void testADirectoryIsNotAPlayableDownload() throws Exception {
        File folder = tempFolder.newFolder("not-a-file.mp3");
        FeedMedia media = media();
        markDownloaded(media, folder);

        assertNull(PlaybackManager.existingLocalCopy(media));
    }

    @Test
    public void testAnUnmarkedFileIsNotADownload() throws Exception {
        File stray = tempFolder.newFile("stray.mp3");
        Files.writeString(stray.toPath(), "x");
        FeedMedia media = media();
        media.setLocalFileUrl(stray.getAbsolutePath());

        assertNull("a path without a download date is not a finished download",
                PlaybackManager.existingLocalCopy(media));
    }
}
