package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class NewEpisodesNoticeTest {
    private static FeedUpdater.RefreshResult result(String feedTitle, String... episodes) {
        List<FeedItem> items = new ArrayList<>();
        for (String title : episodes) {
            FeedItem item = new FeedItem();
            item.setTitle(title);
            items.add(item);
        }
        return new FeedUpdater.RefreshResult(new Feed("http://example.com/" + feedTitle, null, feedTitle),
                items, null);
    }

    @Test
    public void testNothingNewSaysNothing() {
        assertNull(NewEpisodesNotice.of(Collections.emptyList()));
        assertNull(NewEpisodesNotice.of(Arrays.asList(result("Quiet"))));
        FeedUpdater.RefreshResult failed = new FeedUpdater.RefreshResult(
                new Feed("http://example.com/x", null, "Broken"), null, new IOException("offline"));
        assertNull(NewEpisodesNotice.of(Arrays.asList(failed)));
    }

    @Test
    public void testOneEpisodeIsNamed() {
        NewEpisodesNotice notice = NewEpisodesNotice.of(Arrays.asList(result("Quiet"), result("Syntax", "Ep. 900")));
        assertEquals("1 new episode", notice.title);
        assertEquals("Syntax: Ep. 900", notice.text);
    }

    @Test
    public void testSeveralPodcastsBusiestFirstAndTheRestCounted() {
        NewEpisodesNotice notice = NewEpisodesNotice.of(Arrays.asList(
                result("A", "a1"), result("B", "b1", "b2", "b3"), result("C", "c1", "c2"),
                result("D", "d1"), result("E", "e1")));
        assertEquals("8 new episodes", notice.title);
        assertEquals("B (3), C (2), A (1) and 2 more", notice.text);
    }
}
