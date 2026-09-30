package de.danoeh.antennapod.model.feed;

import java.io.Serializable;

/**
 * A host, guest or other contributor from a Podcasting 2.0 {@code <podcast:person>} tag, on the
 * podcast (everyone in every episode) or on one episode.
 */
public class PodcastPerson implements Serializable {
    public final String name;
    /** "host", "guest", ... as the feed spells it; the spec's default is "host". */
    public final String role;
    public final String href;
    public final String img;

    public PodcastPerson(String name, String role, String href, String img) {
        this.name = name != null ? name.trim() : "";
        this.role = role != null && !role.trim().isEmpty() ? role.trim().toLowerCase(java.util.Locale.ROOT) : "host";
        this.href = href != null && !href.trim().isEmpty() ? href.trim() : null;
        this.img = img != null && !img.trim().isEmpty() ? img.trim() : null;
    }
}
