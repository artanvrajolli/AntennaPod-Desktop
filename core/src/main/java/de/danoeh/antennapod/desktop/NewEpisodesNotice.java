package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import java.util.List;

/**
 * The words of the "new episodes" notification after a refresh of every subscription: one
 * notification for the whole refresh, naming the podcasts with the most new episodes first.
 */
public final class NewEpisodesNotice {
    /** Podcasts named before the rest are summed up as "and N more". */
    static final int NAMED = 3;

    public final String title;
    public final String text;

    private NewEpisodesNotice(String title, String text) {
        this.title = title;
        this.text = text;
    }

    /** The notice for these refresh results, or null when nothing new arrived. */
    public static NewEpisodesNotice of(List<FeedUpdater.RefreshResult> results) {
        java.util.List<FeedUpdater.RefreshResult> withNew = new java.util.ArrayList<>();
        int total = 0;
        for (FeedUpdater.RefreshResult result : results) {
            if (result.error == null && result.newEpisodes != null && !result.newEpisodes.isEmpty()) {
                withNew.add(result);
                total += result.newEpisodes.size();
            }
        }
        if (total == 0) {
            return null;
        }
        withNew.sort((a, b) -> Integer.compare(b.newEpisodes.size(), a.newEpisodes.size()));
        String title = total == 1 ? Messages.get("count.new_episode.one")
                : Messages.format("count.new_episode.other", total);
        if (withNew.size() == 1 && total == 1) {
            FeedItem only = withNew.get(0).newEpisodes.get(0);
            return new NewEpisodesNotice(title, feedTitle(withNew.get(0)) + ": " + only.getTitle());
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(NAMED, withNew.size()); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(feedTitle(withNew.get(i))).append(" (").append(withNew.get(i).newEpisodes.size()).append(')');
        }
        if (withNew.size() > NAMED) {
            text.append(' ').append(Messages.format("notice.more", withNew.size() - NAMED));
        }
        return new NewEpisodesNotice(title, text.toString());
    }

    private static String feedTitle(FeedUpdater.RefreshResult result) {
        String title = result.feed != null ? result.feed.getTitle() : null;
        return title != null && !title.isEmpty() ? title : Messages.get("notice.unknown_podcast");
    }
}
