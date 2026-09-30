package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.BasicAuthorizationInterceptor;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Logins for password-protected feeds (premium and Patreon-style feeds use HTTP basic auth).
 *
 * <p>Logins are kept by host, and are sent only when that server answers a request with a 401
 * challenge: by the OkHttp interceptor for feed fetches and downloads, and by the default
 * {@link Authenticator} for streaming, since JavaFX opens its streams through
 * {@code HttpURLConnection} rather than OkHttp.
 */
public final class FeedCredentials {
    private static volatile Map<String, Login> byHost = Collections.emptyMap();
    private static boolean installed;

    private FeedCredentials() {
    }

    public static final class Login {
        public final String username;
        public final String password;

        public Login(String username, String password) {
            this.username = username != null ? username : "";
            this.password = password != null ? password : "";
        }
    }

    /** Hooks the lookup into both HTTP stacks; later calls do nothing. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        BasicAuthorizationInterceptor.setCredentialLookup(url -> {
            Login login = forHost(url.host());
            return login != null ? login.username + ":" + login.password : null;
        });
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.SERVER) {
                    return null;
                }
                Login login = forHost(getRequestingHost());
                return login != null
                        ? new PasswordAuthentication(login.username, login.password.toCharArray())
                        : null;
            }
        });
    }

    /** Replaces every known login, as read from the database. */
    public static void setAll(Map<String, Login> logins) {
        byHost = Collections.unmodifiableMap(new HashMap<>(logins));
    }

    /** Adds or replaces the login for one host, e.g. while a new subscription is fetched. */
    public static synchronized void put(String host, Login login) {
        if (host == null || login == null) {
            return;
        }
        Map<String, Login> next = new HashMap<>(byHost);
        next.put(host.toLowerCase(Locale.ROOT), login);
        byHost = Collections.unmodifiableMap(next);
    }

    public static Login forHost(String host) {
        return host != null ? byHost.get(host.toLowerCase(Locale.ROOT)) : null;
    }

    /** The lower-cased host of a URL, or null when it has none. */
    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The login written into a URL as {@code https://user:pass@host/...}, or null. */
    public static Login fromUserInfo(String url) {
        String info = userInfoOf(url);
        if (info == null || info.isEmpty()) {
            return null;
        }
        int colon = info.indexOf(':');
        String user = colon >= 0 ? info.substring(0, colon) : info;
        String pass = colon >= 0 ? info.substring(colon + 1) : "";
        return new Login(decode(user), decode(pass));
    }

    /** The URL without a {@code user:pass@} part, which must not end up stored or synced. */
    public static String withoutUserInfo(String url) {
        String info = userInfoOf(url);
        if (info == null) {
            return url;
        }
        return url.replaceFirst("(?i)^([a-z][a-z0-9+.-]*://)" + java.util.regex.Pattern.quote(info) + "@",
                "$1");
    }

    private static String userInfoOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return URI.create(url.trim()).getRawUserInfo();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String decode(String part) {
        try {
            return java.net.URLDecoder.decode(part.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return part;
        }
    }
}
