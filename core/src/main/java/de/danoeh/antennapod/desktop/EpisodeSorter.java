package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import java.util.Comparator;
import java.util.List;

public final class EpisodeSorter {
    public static final String NEWEST = "newest";
    public static final String OLDEST = "oldest";
    public static final String SHORTEST = "shortest";
    public static final String LONGEST = "longest";
    public static final String TITLE = "title";

    private EpisodeSorter() {
    }

    public static void sort(List<FeedItem> items, String sortCode) {
        switch (sortCode != null ? sortCode : NEWEST) {
            case OLDEST:
                items.sort(Comparator.comparing(FeedItem::getPubDate,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                break;
            case SHORTEST:
                items.sort(Comparator.comparingInt(EpisodeSorter::durationOf));
                break;
            case LONGEST:
                items.sort(Comparator.comparingInt(EpisodeSorter::durationOf).reversed());
                break;
            case TITLE:
                items.sort(Comparator.comparing(item -> String.valueOf(item.getTitle()),
                        String.CASE_INSENSITIVE_ORDER));
                break;
            default:
                break;
        }
    }

    private static int durationOf(FeedItem item) {
        return item.getMedia() != null ? Math.max(item.getMedia().getDuration(), 0) : 0;
    }
}
