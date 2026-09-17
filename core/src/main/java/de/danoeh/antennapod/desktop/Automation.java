package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedFilter;
import de.danoeh.antennapod.model.feed.FeedItem;

public final class Automation {
    private Automation() {
    }

    public static boolean shouldAutoDownload(FeedItem item, FeedPrefs prefs, boolean globalDefault) {
        if (item.getMedia() == null || item.getMedia().getDownloadUrl() == null) {
            return false;
        }
        if (!prefs.effectiveAutoDownload(globalDefault)) {
            return false;
        }
        FeedFilter filter = new FeedFilter(prefs.includeFilter, prefs.excludeFilter, prefs.minDurationSec);
        return filter.shouldAutoDownload(item);
    }
}
