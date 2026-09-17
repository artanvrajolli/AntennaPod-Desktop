package de.danoeh.antennapod.desktop;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

public final class Shownotes {
    private static final Safelist ALLOWLIST = Safelist.basicWithImages()
            .addAttributes("a", "href")
            .addAttributes("img", "src", "alt", "title")
            .addAttributes("p", "style")
            .preserveRelativeLinks(false);

    private Shownotes() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        if (!html.contains("<")) {
            return escapePlainText(html);
        }
        return Jsoup.clean(html, "", ALLOWLIST);
    }

    public static String toPage(String title, String html) {
        StringBuilder page = new StringBuilder();
        page.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        page.append("<style>body{font-family:sans-serif;line-height:1.5;max-width:800px;margin:16px;}"
                + "img{max-width:100%;height:auto;}a{color:#1a73e8;}</style></head><body>");
        if (title != null && !title.isEmpty()) {
            page.append("<h2>").append(escapePlainText(title)).append("</h2>");
        }
        page.append(sanitize(html));
        page.append("</body></html>");
        return page.toString();
    }

    public static String plainText(String html) {
        if (html == null) {
            return "";
        }
        return Jsoup.parseBodyFragment(html).body().wholeText().trim();
    }

    private static String escapePlainText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
