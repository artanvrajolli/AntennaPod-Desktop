package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class FeedStartTest {
    @Test
    public void testPicksLatestInProgress() {
        FeedItem older = episode(1, 60_000, 3_600_000, 1000);
        FeedItem newer = episode(2, 120_000, 3_600_000, 2000);
        FeedItem untouched = episode(3, 0, 3_600_000, 3000);
        assertEquals(newer, DesktopApp.latestInProgress(Arrays.asList(older, newer, untouched)));
    }

    @Test
    public void testSkipsFinishedAndUnplayed() {
        FeedItem finished = episode(1, 3_600_000, 3_600_000, 2000);
        FeedItem fresh = episode(2, 0, 3_600_000, 3000);
        assertNull(DesktopApp.latestInProgress(Arrays.asList(finished, fresh)));
        assertNull(DesktopApp.latestInProgress(new ArrayList<>()));
    }

    @Test
    public void testFallsBackToFirstWhenNoTimestamps() {
        FeedItem first = episode(1, 60_000, 3_600_000, 0);
        FeedItem second = episode(2, 120_000, 3_600_000, 0);
        assertEquals(first,
                DesktopApp.latestInProgress(Arrays.asList(first, second)));
    }

    private static FeedItem episode(long id, int position, int duration, long lastPlayed) {
        FeedItem item = new FeedItem();
        item.setId(id);
        FeedMedia media = new FeedMedia(id, item, duration, position, 0, "audio/mpeg",
                null, "http://example.com/" + id, 0, null, 0, lastPlayed);
        item.setMedia(media);
        return item;
    }
}
