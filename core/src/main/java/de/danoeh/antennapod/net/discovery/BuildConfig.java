package de.danoeh.antennapod.net.discovery;

public final class BuildConfig {
    public static final String PODCASTINDEX_API_KEY = readKey("PODCASTINDEX_API_KEY", "");
    public static final String PODCASTINDEX_API_SECRET = readKey("PODCASTINDEX_API_SECRET", "");

    private BuildConfig() {
    }

    private static String readKey(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null) {
            return value;
        }
        return System.getProperty("antennapod." + name.toLowerCase(), fallback);
    }
}
