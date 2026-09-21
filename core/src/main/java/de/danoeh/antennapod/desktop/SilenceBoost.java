package de.danoeh.antennapod.desktop;

/**
 * How fast silence may be fast-forwarded through, and when the fast-forward has to be given up.
 *
 * <p>Playing a network stream at eight times speed asks the network for eight times the bitrate.
 * When it cannot keep up, the player drains its buffer and stalls — and because JavaFX only
 * delivers audio spectrum data while a player is actually playing, nothing was left to tell the
 * fast-forward to stop. The player would come back, drain the buffer again at the boosted rate,
 * and stall again, until the episode was abandoned. So a stream gets a far lower ceiling than a
 * file on disk does, and a stall gives the boost up for a while — for longer each time it happens,
 * so a stream that cannot take the boost stops being asked to.
 */
public final class SilenceBoost {
    /** Silence is fast-forwarded this many times faster than the episode's normal speed. */
    public static final double BOOST = 4.0;
    /** Nothing plays faster than this, however fast the episode's own speed is. */
    public static final double MAX_RATE = 8.0;
    /** A stream has to arrive as fast as it is played, so it gets a much lower ceiling. */
    public static final double MAX_STREAM_RATE = 3.0;
    /** How long the fast-forward is given up for after the first stall. */
    public static final long FIRST_BACKOFF_MS = 30_000;
    /** The longest it is given up for, however often the stream stalls. */
    public static final long MAX_BACKOFF_MS = 10 * 60_000;

    private long blockedUntilMs;
    private long backoffMs;
    private boolean stalling;

    /** The rate silence plays at: the episode's own speed boosted, within the source's ceiling. */
    public static float rateFor(float baseSpeed, boolean fromFile) {
        double ceiling = fromFile ? MAX_RATE : MAX_STREAM_RATE;
        return (float) Math.max(baseSpeed, Math.min(baseSpeed * BOOST, ceiling));
    }

    /**
     * The player has run out of data. A run of stalls counts once: the wait only grows when the
     * player managed to play again in between, so a single dry buffer is not punished ten times a
     * second by the caller that polls it.
     */
    public void onStalled(long nowMs) {
        if (stalling) {
            return;
        }
        stalling = true;
        backoffMs = backoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        blockedUntilMs = nowMs + backoffMs;
    }

    /** The player is playing again, so the next stall is a new one. */
    public void onPlaying() {
        stalling = false;
    }

    public boolean isAllowed(long nowMs) {
        return nowMs >= blockedUntilMs;
    }

    /** A different episode starts with a clean slate. */
    public void reset() {
        blockedUntilMs = 0;
        backoffMs = 0;
        stalling = false;
    }
}
