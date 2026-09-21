package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.IOException;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Finds out whether a newer build has been released. Releases are published to GitHub by the
 * project's own workflow, so the release list is the only thing that has to be asked.
 *
 * <p>The parsing and the version comparison are separated from the network call, because they are
 * where the decisions are made: whether a build is newer, and which file of a release is the one
 * to install.
 */
public final class UpdateChecker {
    /** Releases of the project this build came from. */
    static final String RELEASES_URL =
            "https://api.github.com/repos/artanvrajolli/AntennaPod-Desktop/releases/latest";
    /** A build run from source has no released version to compare against. */
    public static final String DEV_VERSION = "dev";

    private UpdateChecker() {
    }

    /** A published release, and the installer within it if it has one. */
    public static final class Release {
        public final String version;
        public final String notes;
        public final String pageUrl;
        public final String installerUrl;
        public final String installerName;
        public final long installerSize;

        Release(String version, String notes, String pageUrl, String installerUrl,
                String installerName, long installerSize) {
            this.version = version;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.installerUrl = installerUrl;
            this.installerName = installerName;
            this.installerSize = installerSize;
        }

        /** Whether this release carries something the app can install by itself. */
        public boolean hasInstaller() {
            return installerUrl != null && installerName != null && installerSize > 0;
        }
    }

    /**
     * The newest release, or null if the project has none. Throws if it could not be asked, so a
     * check the user started can say what went wrong.
     */
    public static Release fetchLatest() throws IOException {
        Request request = new Request.Builder().url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw new IOException("Update check failed: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Update check returned nothing");
            }
            return parse(body.string());
        }
    }

    /**
     * Reads a GitHub release into what the app needs. The installer is the per-user setup exe the
     * release workflow publishes; a release without one can still be opened in a browser.
     */
    static Release parse(String json) {
        JSONObject release = new JSONObject(json);
        String tag = release.optString("tag_name", "");
        String version = stripLeadingV(tag);
        if (version.isEmpty()) {
            return null;
        }
        String notes = release.optString("body", "");
        String pageUrl = release.optString("html_url", "");
        JSONArray assets = release.optJSONArray("assets");
        String installerUrl = null;
        String installerName = null;
        long installerSize = 0;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (!name.toLowerCase(java.util.Locale.US).endsWith(".exe")) {
                continue;
            }
            installerUrl = asset.optString("browser_download_url", null);
            installerName = name;
            installerSize = asset.optLong("size", 0);
            break;
        }
        return new Release(version, notes, pageUrl, installerUrl, installerName, installerSize);
    }

    private static String stripLeadingV(String tag) {
        String trimmed = tag == null ? "" : tag.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    /** Whether this build's version is one that can be compared against a release at all. */
    public static boolean isComparable(String version) {
        return version != null && !version.isEmpty() && !DEV_VERSION.equalsIgnoreCase(version);
    }

    /** Whether {@code candidate} is a later version than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        return isComparable(current) && candidate != null && !candidate.isEmpty()
                && compareVersions(candidate, current) > 0;
    }

    /**
     * Compares dotted versions a part at a time, so 0.1.10 comes after 0.1.9 where a plain string
     * comparison would put it before. A missing part counts as zero, so 0.2 and 0.2.0 are equal.
     * Anything that is not a number sorts as zero rather than throwing, because a release name is
     * not ours to police.
     */
    static int compareVersions(String left, String right) {
        String[] leftParts = split(left);
        String[] rightParts = split(right);
        int parts = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < parts; i++) {
            int comparison = Integer.compare(
                    numberAt(leftParts, i), numberAt(rightParts, i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String[] split(String version) {
        // a suffix such as 1.2.3-beta1 compares on its numbers; the suffix itself is ignored
        String cleaned = version == null ? "" : version.trim();
        int dash = cleaned.indexOf('-');
        if (dash >= 0) {
            cleaned = cleaned.substring(0, dash);
        }
        return cleaned.isEmpty() ? new String[0] : cleaned.split("\\.");
    }

    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
