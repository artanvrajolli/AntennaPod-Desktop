package de.danoeh.antennapod.storage.importexport;

import android.util.Log;
import de.danoeh.antennapod.model.feed.Feed;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.List;
import org.apache.commons.io.IOUtils;

/** Writes HTML documents. */
public class HtmlWriter {
    private static final String TAG = "HtmlWriter";

    /**
     * Takes a list of feeds and a writer and writes those into an HTML
     * document.
     */
    public static void writeDocument(List<Feed> feeds, Writer writer)
            throws IllegalArgumentException, IllegalStateException, IOException {
        Log.d(TAG, "Starting to write document");

        InputStream templateStream = HtmlWriter.class.getResourceAsStream("html-export-template.html");
        if (templateStream == null) {
            throw new IOException("HTML export template not found");
        }
        String template = IOUtils.toString(templateStream, "UTF-8");
        template = template.replaceAll("\\{TITLE\\}", "Subscriptions");
        String[] templateParts = template.split("\\{FEEDS\\}");

        writer.append(templateParts[0]);
        for (Feed feed : feeds) {
            if (feed.getState() != Feed.STATE_SUBSCRIBED) {
                continue;
            }
            writer.append("<li><div><img src=\"");
            writer.append(escape(feed.getImageUrl()));
            writer.append("\" /><p>");
            writer.append(escape(feed.getTitle()));
            writer.append(" <span><a href=\"");
            writer.append(escape(feed.getLink()));
            writer.append("\">Website</a> • <a href=\"");
            writer.append(escape(feed.getDownloadUrl()));
            writer.append("\">Feed</a></span></p></div></li>\n");
        }
        writer.append(templateParts[1]);
        Log.d(TAG, "Finished writing document");
    }

    /** Feed values are written into an HTML document the user opens: escape them, always. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            switch (value.charAt(i)) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(value.charAt(i));
            }
        }
        return escaped.toString();
    }
}
