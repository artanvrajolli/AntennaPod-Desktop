package de.danoeh.antennapod.model.feed;

import java.io.Serializable;

/** A highlight of an episode from a Podcasting 2.0 {@code <podcast:soundbite>} tag. */
public class Soundbite implements Serializable {
    public final int startMs;
    public final int durationMs;
    /** The feed's own title for it, or "" when it gave none. */
    public final String title;

    public Soundbite(int startMs, int durationMs, String title) {
        this.startMs = Math.max(0, startMs);
        this.durationMs = Math.max(0, durationMs);
        this.title = title != null ? title.trim() : "";
    }
}
