package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.Feed;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class FeedSorter {
    private FeedSorter() {
    }

    public static void sortByLastPlayed(List<Feed> feeds, Map<Long, Long> lastPlayedTimes) {
        feeds.sort(Comparator.comparingLong(
                (Feed feed) -> lastPlayedTimes.getOrDefault(feed.getId(), 0L)).reversed());
    }
}
