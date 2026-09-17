package android.support.v4.media;

public final class MediaBrowserCompat {
    private MediaBrowserCompat() {
    }

    public static final class MediaItem {
        public static final int FLAG_PLAYABLE = 1;
        public static final int FLAG_BROWSABLE = 2;

        private final MediaDescriptionCompat description;
        private final int flags;

        public MediaItem(MediaDescriptionCompat description, int flags) {
            this.description = description;
            this.flags = flags;
        }

        public MediaDescriptionCompat getDescription() {
            return description;
        }

        public int getFlags() {
            return flags;
        }
    }
}
