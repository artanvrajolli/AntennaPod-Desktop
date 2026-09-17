package android.util;

import de.danoeh.antennapod.desktop.xml.SimpleXmlSerializer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

public final class Xml {
    private Xml() {
    }

    public static XmlSerializer newSerializer() {
        return new SimpleXmlSerializer();
    }

    public static XmlPullParser newPullParser() throws XmlPullParserException {
        try {
            return XmlPullParserFactory.newInstance().newPullParser();
        } catch (XmlPullParserException e) {
            throw e;
        } catch (Exception e) {
            throw new XmlPullParserException(e.getMessage());
        }
    }
}
