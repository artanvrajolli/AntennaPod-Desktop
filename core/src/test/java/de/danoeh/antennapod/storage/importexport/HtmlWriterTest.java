package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.Feed;
import java.io.StringWriter;
import java.util.List;
import org.junit.Test;

public class HtmlWriterTest {
    @Test
    public void testFeedValuesAreEscaped() throws Exception {
        Feed feed = new Feed("https://example.com/feed.xml", null,
                "<script>alert('title')</script>");
        feed.setLink("https://example.com/\" onmouseover=\"alert(1)");
        feed.setImageUrl("https://example.com/x\" onerror=\"alert(2)");
        StringWriter writer = new StringWriter();

        HtmlWriter.writeDocument(List.of(feed), writer);

        String html = writer.toString();
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("onerror=\"alert"));
        assertFalse(html.contains("onmouseover=\"alert"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&quot;"));
    }
}
