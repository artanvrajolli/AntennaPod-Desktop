package de.danoeh.antennapod.desktop;

import java.text.MessageFormat;
import java.util.Locale;
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

    private Messages() {
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
