package de.danoeh.antennapod.desktop;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The user-visible text of the app, read from {@code i18n/messages.properties} (UTF-8) so it
 * can be translated by adding a {@code messages_<lang>.properties} next to it.
 *
 * <p>Two ways in, and the rule for choosing between them:
 * <ul>
 *   <li>{@link #get} returns the value exactly as written: no {@link MessageFormat} processing,
 *       so an apostrophe stays a single {@code '} and braces are plain text. Use it for every
 *       string without placeholders.</li>
 *   <li>{@link #format} runs the value through {@link MessageFormat}, filling {@code {0}},
 *       {@code {1}}, ... Use it only for strings with placeholders. In those values an
 *       apostrophe must be written twice ({@code ''}) and a literal brace quoted
 *       ({@code '{'}), as MessageFormat requires.</li>
 * </ul>
 *
 * <p>Arguments are inserted as {@code String.valueOf(arg)}, the text string concatenation would
 * give, so numbers never pick up locale grouping ({@code 1234}, not {@code 1,234}) and dates are
 * formatted by the caller.
 *
 * <p>A missing key never breaks the UI: the key itself is shown instead.
 */
public final class Messages {
    static final String BUNDLE = "i18n.messages";

    /**
     * The languages the app ships a bundle for, by language tag, each named in its own language
     * so it can be found whatever the current one is. Add a row with each new bundle.
     */
    public static final Map<String, String> LANGUAGES = languages();

    private static Map<String, String> languages() {
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("en", "English");
        languages.put("sq", "Shqip");
        languages.put("zh-CN", "简体中文");
        return Collections.unmodifiableMap(languages);
    }

    private Messages() {
    }

    /**
     * The locale to show the UI in for a saved language tag, or null to follow the system: for an
     * empty tag and for one the app has no bundle for.
     */
    public static Locale localeFor(String tag) {
        return tag != null && LANGUAGES.containsKey(tag) ? Locale.forLanguageTag(tag) : null;
    }

    /**
     * Switches the UI to the language saved as {@code tag} by making it the default locale. Call
     * it before the first lookup: the text is loaded once, so a change shows after a restart.
     */
    public static void applyLanguage(String tag) {
        Locale locale = localeFor(tag);
        if (locale != null) {
            Locale.setDefault(locale);
        }
    }

    /** Loaded once, on first use. */
    private static final class Holder {
        static final ResourceBundle BUNDLE_INSTANCE = load();

        private static ResourceBundle load() {
            try {
                return ResourceBundle.getBundle(BUNDLE, Locale.getDefault());
            } catch (MissingResourceException e) {
                return null;
            }
        }
    }

    /** The text for {@code key}, verbatim; the key itself when there is no such text. */
    public static String get(String key) {
        ResourceBundle bundle = Holder.BUNDLE_INSTANCE;
        if (bundle == null || key == null) {
            return key;
        }
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * The text for {@code key} with its placeholders filled in from {@code args}; the key itself
     * when there is no such text.
     */
    public static String format(String key, Object... args) {
        String pattern = get(key);
        if (pattern == null || pattern.equals(key)) {
            return pattern;
        }
        Object[] texts = new Object[args == null ? 0 : args.length];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = String.valueOf(args[i]);
        }
        ResourceBundle bundle = Holder.BUNDLE_INSTANCE;
        Locale locale = bundle != null ? bundle.getLocale() : Locale.ROOT;
        return new MessageFormat(pattern, locale).format(texts);
    }
}
