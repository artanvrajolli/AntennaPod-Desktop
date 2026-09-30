package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;

/**
 * The episode list's state filter, next to its text search. Each check reads fields the loaded
 * episode already carries, so it is cheap enough to run for every row on the FX thread.
 */
public enum EpisodeFilter {
    ALL("All episodes"),
    UNPLAYED("Unplayed"),
    IN_PROGRESS("In progress"),
    DOWNLOADED("Downloaded"),
    FAVORITES("Favorites");

    private final String label;

    EpisodeFilter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public boolean matches(FeedItem item) {
        if (item == null) {
            return false;
        }
        FeedMedia media = item.getMedia();
        switch (this) {
            case UNPLAYED:
                return !item.isPlayed();
            case IN_PROGRESS:
                return !item.isPlayed() && media != null && media.getPosition() > 0;
            case DOWNLOADED:
                return media != null && media.localFileAvailable();
            case FAVORITES:
                return item.isTagged(FeedItem.TAG_FAVORITE);
            default:
                return true;
        }
    }

    /** The filter stored under this name, or {@link #ALL} for anything unknown. */
    public static EpisodeFilter fromName(String name) {
        if (name != null) {
            for (EpisodeFilter filter : values()) {
                if (filter.name().equals(name)) {
                    return filter;
                }
            }
        }
        return ALL;
    }
}
