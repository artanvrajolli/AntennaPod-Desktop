package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.Test;

public class DesktopSorterTest {
    private static FeedItem item(String title, long pubDate, int durationSec) {
        FeedItem item = new FeedItem();
        item.setTitle(title);
        item.setPubDate(new Date(pubDate));
        FeedMedia media = new FeedMedia(item, "http://example.com/" + title + ".mp3", 1000, "audio/mpeg");
        media.setDuration(durationSec * 1000);
        item.setMedia(media);
        return item;
    }

    private static List<String> titles(List<FeedItem> items) {
        List<String> titles = new ArrayList<>();
        for (FeedItem item : items) {
            titles.add(item.getTitle());
        }
        return titles;
    }

    private static List<FeedItem> shuffled() {
        List<FeedItem> items = new ArrayList<>();
        items.add(item("Charlie", 3000, 300));
        items.add(item("Alpha", 1000, 600));
        items.add(item("Bravo", 2000, 100));
        return items;
    }

    @Test
    public void testOldestFirstPlaysStartToEnd() {
        List<FeedItem> items = shuffled();
        EpisodeSorter.sort(items, EpisodeSorter.OLDEST);
        assertEquals(java.util.Arrays.asList("Alpha", "Bravo", "Charlie"), titles(items));
    }

    @Test
    public void testNewestKeepsDbOrder() {
        List<FeedItem> items = shuffled();
        EpisodeSorter.sort(items, EpisodeSorter.NEWEST);
        assertEquals(java.util.Arrays.asList("Charlie", "Alpha", "Bravo"), titles(items));
    }

    @Test
    public void testShortestLongestTitle() {
        List<FeedItem> items = shuffled();
        EpisodeSorter.sort(items, EpisodeSorter.SHORTEST);
        assertEquals(java.util.Arrays.asList("Bravo", "Charlie", "Alpha"), titles(items));
        EpisodeSorter.sort(items, EpisodeSorter.LONGEST);
        assertEquals(java.util.Arrays.asList("Alpha", "Charlie", "Bravo"), titles(items));
        EpisodeSorter.sort(items, EpisodeSorter.TITLE);
        assertEquals(java.util.Arrays.asList("Alpha", "Bravo", "Charlie"), titles(items));
    }
}
