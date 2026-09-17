package android.util;

public final class Base64 {
    public static final int NO_WRAP = 2;

    private Base64() {
    }

    public static String encodeToString(byte[] input, int flags) {
        if ((flags & NO_WRAP) != 0) {
            return java.util.Base64.getEncoder().encodeToString(input);
        }
        return java.util.Base64.getMimeEncoder().encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getMimeDecoder().decode(str);
    }
}
