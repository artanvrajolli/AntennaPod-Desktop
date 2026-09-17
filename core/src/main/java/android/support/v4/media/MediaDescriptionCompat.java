package android.support.v4.media;

import android.net.Uri;

public final class MediaDescriptionCompat {
    private final String mediaId;
    private final CharSequence title;
    private final CharSequence subtitle;
    private final CharSequence description;
    private final Uri iconUri;

    private MediaDescriptionCompat(Builder builder) {
        this.mediaId = builder.mediaId;
        this.title = builder.title;
        this.subtitle = builder.subtitle;
        this.description = builder.description;
        this.iconUri = builder.iconUri;
    }

    public String getMediaId() {
        return mediaId;
    }

    public CharSequence getTitle() {
        return title;
    }

    public CharSequence getSubtitle() {
        return subtitle;
    }

    public CharSequence getDescription() {
        return description;
    }

    public Uri getIconUri() {
        return iconUri;
    }

    public static final class Builder {
        private String mediaId;
        private CharSequence title;
        private CharSequence subtitle;
        private CharSequence description;
        private Uri iconUri;

        public Builder setMediaId(String mediaId) {
            this.mediaId = mediaId;
            return this;
        }

        public Builder setTitle(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder setSubtitle(CharSequence subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder setDescription(CharSequence description) {
            this.description = description;
            return this;
        }

        public Builder setIconUri(Uri iconUri) {
            this.iconUri = iconUri;
            return this;
        }

        public MediaDescriptionCompat build() {
            return new MediaDescriptionCompat(this);
        }
    }
}
