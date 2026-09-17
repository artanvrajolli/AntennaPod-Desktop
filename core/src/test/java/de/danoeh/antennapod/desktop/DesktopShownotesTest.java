package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DesktopShownotesTest {
    @Test
    public void testScriptsRemovedLinksKept() {
        String html = "<p>Hello <b>world</b></p><script>alert(1)</script>"
                + "<a href=\"https://example.com\" onclick=\"evil()\">link</a>";
        String clean = Shownotes.sanitize(html);
        assertTrue(clean.contains("Hello"));
        assertFalse(clean.contains("<script"));
        assertFalse(clean.contains("onclick"));
        assertTrue(clean.contains("href=\"https://example.com\""));
    }

    @Test
    public void testPlainTextWrapped() {
        String page = Shownotes.toPage("Title", "Just some text", false);
        assertTrue(page.contains("Just some text"));
        assertTrue(page.contains("<h2>Title</h2>"));
    }

    @Test
    public void testDarkPageUsesDarkColors() {
        String lightPage = Shownotes.toPage("Title", "text", false);
        String darkPage = Shownotes.toPage("Title", "text", true);
        assertTrue(lightPage.contains("background:#ffffff"));
        assertTrue(darkPage.contains("background:#1e1e1e"));
        assertTrue(darkPage.contains("color:#ececec"));
    }

    @Test
    public void testPlainTextExtraction() {
        assertTrue(Shownotes.plainText("<p>First <b>notes</b></p>").contains("First notes"));
    }
}
