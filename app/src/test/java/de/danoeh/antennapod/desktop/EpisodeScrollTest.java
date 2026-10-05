package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class EpisodeScrollTest {
    @Test
    public void testFindsPlayingEpisodeDeepInABigList() {
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            items.add(itemWithMedia(i, 1000 + i));
        }
        assertEquals(500, DesktopApp.indexOfMedia(items, 1500));
    }

    @Test
    public void testPlayingEpisodeLandsMidList() {
        // ten rows on screen: episode 500 scrolls so row 495 is at the top, leaving
        // 499 above and 501 below the playing row
        assertEquals(495, DesktopApp.centeredScrollTarget(500, 10));
        assertEquals(0, DesktopApp.centeredScrollTarget(2, 10));
        assertEquals(0, DesktopApp.centeredScrollTarget(0, 10));
    }

    @Test
    public void testMissingOrFilteredEpisodeGivesMinusOne() {
        List<FeedItem> items = new ArrayList<>();
        items.add(itemWithMedia(1, 11));
        items.add(new FeedItem());
        assertEquals(-1, DesktopApp.indexOfMedia(items, 999));
        assertEquals(-1, DesktopApp.indexOfMedia(null, 11));
        assertEquals(-1, DesktopApp.indexOfMedia(new ArrayList<>(), 11));
    }

    @Test
    public void testLeftOffIsTheMostRecentlyPlayedEpisode() {
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            items.add(itemWithMedia(i, 1000 + i));
        }
        items.get(10).getMedia().setLastPlayedTimeStatistics(5_000L);
        items.get(240).getMedia().setLastPlayedTimeStatistics(9_000L);
        items.get(120).getMedia().setLastPlayedTimeStatistics(7_000L);
        assertEquals(240, DesktopApp.indexOfLastPlayed(items));
    }

    @Test
    public void testLeftOffAlsoReadsThePlaybackHistory() {
        // episodes played before the statistics stamp existed only carry the history date
        List<FeedItem> items = new ArrayList<>();
        items.add(itemWithMedia(1, 11));
        items.add(itemWithMedia(2, 12));
        items.get(0).getMedia().setLastPlayedTimeStatistics(5_000L);
        items.get(1).getMedia().setLastPlayedTimeHistory(new java.util.Date(8_000L));
        assertEquals(1, DesktopApp.indexOfLastPlayed(items));
    }

    @Test
    public void testNothingPlayedLeavesTheListAtTheTop() {
        List<FeedItem> items = new ArrayList<>();
        items.add(itemWithMedia(1, 11));
        items.add(new FeedItem());
        assertEquals(-1, DesktopApp.indexOfLastPlayed(items));
        assertEquals(-1, DesktopApp.indexOfLastPlayed(null));
        assertEquals(-1, DesktopApp.indexOfLastPlayed(new ArrayList<>()));
    }

    private static FeedItem itemWithMedia(long itemId, long mediaId) {
        FeedItem item = new FeedItem();
        item.setId(itemId);
        FeedMedia media = new FeedMedia(mediaId, item, 3600000, 0, 0, "audio/mpeg",
                null, "http://example.com/" + mediaId, 0, null, 0, 0);
        item.setMedia(media);
        return item;
    }
}
