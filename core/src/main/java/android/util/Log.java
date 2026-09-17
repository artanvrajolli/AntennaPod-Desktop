package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Log {
    private Log() {
    }

    private static Logger logger(String tag) {
        return Logger.getLogger("AntennaPod." + tag);
    }

    public static int d(String tag, String msg) {
        logger(tag).fine(String.valueOf(msg));
        return 0;
    }

    public static int i(String tag, String msg) {
        logger(tag).info(String.valueOf(msg));
        return 0;
    }

    public static int w(String tag, String msg) {
        logger(tag).warning(String.valueOf(msg));
        return 0;
    }

    public static int e(String tag, String msg) {
        logger(tag).severe(String.valueOf(msg));
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        logger(tag).log(Level.SEVERE, String.valueOf(msg), tr);
        return 0;
    }

    public static int v(String tag, String msg) {
        logger(tag).finest(String.valueOf(msg));
        return 0;
    }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
