package android.net;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Uri {
    private final String raw;
    private final URI parsed;

    private Uri(String raw, URI parsed) {
        this.raw = raw;
        this.parsed = parsed;
    }

    public static Uri parse(String uriString) {
        if (uriString == null) {
            return null;
        }
        URI parsed = null;
        try {
            parsed = new URI(uriString);
        } catch (Exception e) {
            parsed = null;
        }
        return new Uri(uriString, parsed);
    }

    public boolean isRelative() {
        return parsed != null && !parsed.isAbsolute();
    }

    public boolean isAbsolute() {
        return parsed != null && parsed.isAbsolute();
    }

    public String getScheme() {
        return parsed == null ? null : parsed.getScheme();
    }

    public String getHost() {
        return parsed == null ? null : parsed.getHost();
    }

    public String getQuery() {
        return parsed == null ? null : parsed.getRawQuery();
    }

    public String getQueryParameter(String key) {
        if (parsed == null || parsed.getRawQuery() == null) {
            return null;
        }
        for (String pair : parsed.getRawQuery().split("&")) {
            int idx = pair.indexOf('=');
            String name = idx >= 0 ? pair.substring(0, idx) : pair;
            try {
                name = URLDecoder.decode(name, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                // keep raw
            }
            if (name.equals(key)) {
                if (idx < 0) {
                    return "";
                }
                try {
                    return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return pair.substring(idx + 1);
                }
            }
        }
        return null;
    }

    public List<String> getPathSegments() {
        if (parsed == null || parsed.getRawPath() == null) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : parsed.getRawPath().split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    public Builder buildUpon() {
        return new Builder(raw, parsed);
    }

    @Override
    public String toString() {
        if (parsed != null) {
            return parsed.toString();
        }
        return raw;
    }

    public static final class Builder {
        private String scheme;
        private String raw;
        private URI parsed;

        Builder(String raw, URI parsed) {
            this.raw = raw;
            this.parsed = parsed;
        }

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Uri build() {
            if (scheme != null && parsed != null && !parsed.isAbsolute()) {
                try {
                    String ssp = parsed.getRawSchemeSpecificPart();
                    if (ssp != null && ssp.startsWith("//")) {
                        return new Uri(scheme + ":" + ssp, new URI(scheme + ":" + ssp));
                    }
                    return new Uri(scheme + "://" + raw, new URI(scheme + "://" + raw));
                } catch (Exception e) {
                    return new Uri(raw, parsed);
                }
            }
            return new Uri(raw, parsed);
        }
    }
}
