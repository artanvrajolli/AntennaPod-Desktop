package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/** The summary line shown under a podcast's title in the search-result details modal. */
public class PodcastDetailsTest {
    private static Feed feedWith(String language, String... pubDates) throws Exception {
        Feed feed = new Feed("https://example.com/feed.xml", null);
        feed.setLanguage(language);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        List<FeedItem> items = new ArrayList<>();
        for (String pubDate : pubDates) {
            FeedItem item = new FeedItem();
            item.setPubDate(pubDate == null ? null : format.parse(pubDate));
            items.add(item);
        }
        feed.setItems(items);
        return feed;
    }

    @Test
    public void testCountsEpisodesAndNamesTheNewest() throws Exception {
        Feed feed = feedWith("en", "2024-01-05", "2024-03-20", "2023-11-02");
        assertEquals("3 episodes · en · latest 20 Mar 2024", DesktopApp.describePodcast(feed));
    }

    @Test
    public void testASingleEpisodeIsNotPluralised() throws Exception {
        Feed feed = feedWith("de", "2024-01-05");
        assertEquals("1 episode · de · latest 5 Jan 2024", DesktopApp.describePodcast(feed));
    }

    @Test
    public void testMissingLanguageAndDatesAreLeftOut() throws Exception {
        Feed feed = feedWith(null, (String) null);
        assertEquals("1 episode", DesktopApp.describePodcast(feed));
    }

    @Test
    public void testAnEmptyFeedStillDescribesItself() throws Exception {
        Feed feed = feedWith("");
        assertEquals("0 episodes", DesktopApp.describePodcast(feed));
    }

    @Test
    public void testNewestDateIgnoresEpisodesWithoutOne() throws Exception {
        Feed feed = feedWith("en", null, "2022-06-09", null);
        assertEquals(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2022-06-09"),
                DesktopApp.newestPubDate(feed));
    }

    @Test
    public void testNoDatesAtAllMeansNoNewest() throws Exception {
        assertNull(DesktopApp.newestPubDate(feedWith("en", (String) null)));
    }
}
