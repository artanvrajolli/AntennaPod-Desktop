package de.danoeh.antennapod.desktop;

import java.util.Locale;

/**
 * Decides what the toolbar's single "search or subscribe" field should do with its text.
 *
 * <p>Anything that reads as an address subscribes; everything else is a podcast search. The
 * schemes mirror the ones {@code UrlChecker.prepareUrl} normalises, and a scheme-less address
 * needs both a dot and a slash, so a bare {@code example.com} or {@code node.js} still searches.
 */
final class FeedInput {
    private FeedInput() {
    }

    static boolean looksLikeFeedUrl(String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        return text.contains("://")
                || text.toLowerCase(Locale.ROOT).startsWith("pcast:")
                || (text.contains(".") && text.contains("/"));
    }
}
