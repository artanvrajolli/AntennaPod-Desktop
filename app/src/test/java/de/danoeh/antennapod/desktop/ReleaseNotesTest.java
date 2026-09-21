package de.danoeh.antennapod.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Release notes come from GitHub as Markdown and are shown as HTML in the update modal. */
public class ReleaseNotesTest {
    @Test
    public void testBulletsBecomeAList() {
        String html = DesktopApp.releaseNotesHtml("- first\n- second");
        assertEquals("<ul><li>first</li><li>second</li></ul>", html);
    }

    @Test
    public void testAsterisksAlsoCountAsBullets() {
        assertEquals("<ul><li>only</li></ul>", DesktopApp.releaseNotesHtml("* only"));
    }

    @Test
    public void testTheListIsClosedWhenProseFollowsIt() {
        String html = DesktopApp.releaseNotesHtml("- a bullet\nthen a paragraph");
        assertEquals("<ul><li>a bullet</li></ul><p>then a paragraph</p>", html);
    }

    @Test
    public void testBoldIsKept() {
        assertTrue(DesktopApp.releaseNotesHtml("**Installer** notes").contains("<b>Installer</b>"));
    }

    @Test
    public void testMarkupInTheNotesCannotInjectHtml() {
        String html = DesktopApp.releaseNotesHtml("<script>alert(1)</script>");
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    public void testEmptyNotesSaySo() {
        assertTrue(DesktopApp.releaseNotesHtml("").contains("No release notes"));
        assertTrue(DesktopApp.releaseNotesHtml(null).contains("No release notes"));
        assertTrue(DesktopApp.releaseNotesHtml("   \n  ").contains("No release notes"));
    }

    @Test
    public void testBlankLinesAreDropped() {
        assertEquals("<p>one</p><p>two</p>", DesktopApp.releaseNotesHtml("one\n\n\ntwo"));
    }
}
