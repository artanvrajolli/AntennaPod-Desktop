package android.text;

public final class TextUtils {
    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toString().contentEquals(b);
    }

    public static String join(CharSequence delimiter, Iterable tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object token : tokens) {
            if (!first) {
                sb.append(delimiter);
            }
            first = false;
            sb.append(token);
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object token : tokens) {
            if (!first) {
                sb.append(delimiter);
            }
            first = false;
            sb.append(token);
        }
        return sb.toString();
    }

    public static String[] split(String text, String expression) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.split(java.util.regex.Pattern.quote(expression));
    }
}
