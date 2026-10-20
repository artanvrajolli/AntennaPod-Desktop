package de.danoeh.antennapod.parser.feed;

import de.danoeh.antennapod.parser.feed.util.TypeGetter;
import org.apache.commons.io.input.XmlStreamReader;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import de.danoeh.antennapod.model.feed.Feed;

public class FeedHandler {
    public FeedHandlerResult parseFeed(Feed feed) throws SAXException, IOException,
            ParserConfigurationException, UnsupportedFeedtypeException {
        TypeGetter tg = new TypeGetter();
        TypeGetter.Type type = tg.getType(feed);
        SyndHandler handler = new SyndHandler(feed, type);

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        // Feeds come from the network: never resolve external entities or DTDs, or a feed could
        // read local files or make the app fetch arbitrary URLs while parsing.
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        SAXParser saxParser = factory.newSAXParser();
        saxParser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        File file = new File(feed.getLocalFileUrl());
        try (Reader inputStreamReader = new XmlStreamReader(file)) {
            InputSource inputSource = new InputSource(inputStreamReader);
            saxParser.parse(inputSource, handler);
        }
        return new FeedHandlerResult(handler.state.feed, handler.state.alternateUrls, handler.state.redirectUrl);
    }
}
