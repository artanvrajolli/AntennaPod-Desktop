package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.junit.Test;

public class EpisodeFilterTest {
    private static FeedItem item() {
        FeedItem item = new FeedItem();
        item.setTitle("Episode");
        FeedMedia media = new FeedMedia(item, "http://example.com/e.mp3", 1000, "audio/mpeg");
        media.setDuration(60_000);
        item.setMedia(media);
        return item;
    }

    @Test
    public void testAllMatchesEverythingButNull() {
        assertTrue(EpisodeFilter.ALL.matches(item()));
        assertFalse(EpisodeFilter.ALL.matches(null));
    }

    @Test
    public void testUnplayedHidesPlayed() {
        FeedItem item = item();
        assertTrue(EpisodeFilter.UNPLAYED.matches(item));
        item.setPlayed(true);
        assertFalse(EpisodeFilter.UNPLAYED.matches(item));
    }

    @Test
    public void testInProgressNeedsAPositionAndNotPlayed() {
        FeedItem item = item();
        assertFalse(EpisodeFilter.IN_PROGRESS.matches(item));
        item.getMedia().setPosition(5_000);
        assertTrue(EpisodeFilter.IN_PROGRESS.matches(item));
        item.setPlayed(true);
        assertFalse(EpisodeFilter.IN_PROGRESS.matches(item));
    }

    @Test
    public void testDownloadedNeedsTheLocalFile() {
        FeedItem item = item();
        assertFalse(EpisodeFilter.DOWNLOADED.matches(item));
        item.getMedia().setLocalFileUrl("C:/media/e.mp3");
        item.getMedia().setDownloaded(true, System.currentTimeMillis());
        assertTrue(EpisodeFilter.DOWNLOADED.matches(item));
    }

    @Test
    public void testFavoritesFollowTheTag() {
        FeedItem item = item();
        assertFalse(EpisodeFilter.FAVORITES.matches(item));
        item.addTag(FeedItem.TAG_FAVORITE);
        assertTrue(EpisodeFilter.FAVORITES.matches(item));
    }

    @Test
    public void testUnknownStoredNameFallsBackToAll() {
        assertEquals(EpisodeFilter.DOWNLOADED, EpisodeFilter.fromName("DOWNLOADED"));
        assertEquals(EpisodeFilter.ALL, EpisodeFilter.fromName("bogus"));
        assertEquals(EpisodeFilter.ALL, EpisodeFilter.fromName(null));
    }
}
