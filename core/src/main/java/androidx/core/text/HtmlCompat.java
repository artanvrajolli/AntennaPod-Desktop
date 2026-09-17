package androidx.core.text;

import org.jsoup.Jsoup;

public final class HtmlCompat {
    public static final int FROM_HTML_MODE_COMPACT = 0;
    public static final int FROM_HTML_MODE_LEGACY = 1;

    private HtmlCompat() {
    }

    public static Spanned fromHtml(String source, int flags) {
        if (source == null) {
            return new Spanned("");
        }
        String text = Jsoup.parseBodyFragment(source).body().wholeText();
        return new Spanned(text);
    }

    public static final class Spanned implements CharSequence {
        private final String text;

        Spanned(String text) {
            this.text = text;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text.trim();
        }
    }
}
