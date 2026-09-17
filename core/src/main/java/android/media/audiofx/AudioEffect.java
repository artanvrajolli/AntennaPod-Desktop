package android.media.audiofx;

import java.util.UUID;

public class AudioEffect {
    public static final UUID EFFECT_TYPE_LOUDNESS_ENHANCER =
            UUID.fromString("fe3199be-aed0-413f-87bb-11260eb63cf1");

    public static Descriptor[] queryEffects() {
        return new Descriptor[0];
    }

    public static final class Descriptor {
        public UUID type;
        public UUID uuid;
        public String name;
        public String implementor;
        public String connectMode;

        public Descriptor(UUID type) {
            this.type = type;
        }
    }
}
