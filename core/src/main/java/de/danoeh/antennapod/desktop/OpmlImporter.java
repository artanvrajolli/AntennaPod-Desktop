package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.importexport.HtmlWriter;
import de.danoeh.antennapod.storage.importexport.OpmlElement;
import de.danoeh.antennapod.storage.importexport.OpmlReader;
import de.danoeh.antennapod.storage.importexport.OpmlWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public final class OpmlImporter {
    private final DesktopDatabase database;
    private final FeedUpdater feedUpdater;

    public OpmlImporter(DesktopDatabase database, FeedUpdater feedUpdater) {
        this.database = database;
        this.feedUpdater = feedUpdater;
    }

    public ImportResult importFromReader(Reader reader) throws Exception {
        List<OpmlElement> elements = new OpmlReader().readDocument(reader);
        List<String> imported = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (OpmlElement element : elements) {
            if (element.getXmlUrl() == null || element.getXmlUrl().isEmpty()) {
                continue;
            }
            try {
                Feed feed = feedUpdater.subscribe(element.getXmlUrl());
                imported.add(feed.getTitle() != null ? feed.getTitle() : element.getXmlUrl());
            } catch (Exception e) {
                failed.add(element.getXmlUrl() + ": " + e.getMessage());
            }
        }
        return new ImportResult(imported, failed);
    }

    public void exportToWriter(Writer writer) throws Exception {
        OpmlWriter.writeDocument(exportableFeeds(), writer);
    }

    public void exportHtmlToWriter(Writer writer) throws Exception {
        HtmlWriter.writeDocument(exportableFeeds(), writer);
    }

    /** Every subscription but local folders, whose paths mean nothing to another app. */
    private java.util.List<de.danoeh.antennapod.model.feed.Feed> exportableFeeds() throws Exception {
        java.util.List<de.danoeh.antennapod.model.feed.Feed> feeds = new java.util.ArrayList<>();
        for (de.danoeh.antennapod.model.feed.Feed feed : database.getAllFeeds()) {
            if (!feed.isLocalFeed()) {
                feeds.add(feed);
            }
        }
        return feeds;
    }

    public static final class ImportResult {
        public final List<String> imported;
        public final List<String> failed;

        ImportResult(List<String> imported, List<String> failed) {
            this.imported = imported;
            this.failed = failed;
        }
    }
}
