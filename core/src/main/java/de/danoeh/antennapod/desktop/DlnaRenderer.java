package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * A UPnP/DLNA media renderer on the local network (a smart TV, an AV receiver, a network speaker)
 * and the few UPnP AVTransport and RenderingControl actions casting needs, sent as SOAP.
 */
public final class DlnaRenderer {
    static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

    public final String name;
    public final String location;
    final String avTransportUrl;
    final String renderingControlUrl;

    DlnaRenderer(String name, String location, String avTransportUrl, String renderingControlUrl) {
        this.name = name;
        this.location = location;
        this.avTransportUrl = avTransportUrl;
        this.renderingControlUrl = renderingControlUrl;
    }

    /** The renderer's host, which the cast server must be reachable from. */
    public String host() {
        return URI.create(location).getHost();
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DlnaRenderer && ((DlnaRenderer) other).location.equals(location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location);
    }

    /**
     * Reads a device description (the XML at an SSDP LOCATION). Null when it is no renderer:
     * without an AVTransport service there is nothing to send media to.
     */
    static DlnaRenderer fromDescription(String location, String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // descriptions come from the network: no external entities, no DTDs
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            String base = text(doc.getDocumentElement(), "URLBase");
            if (base == null || base.isEmpty()) {
                base = location;
            }
            String name = null;
            String transport = null;
            String rendering = null;
            NodeList devices = doc.getElementsByTagName("device");
            for (int d = 0; d < devices.getLength(); d++) {
                Element device = (Element) devices.item(d);
                NodeList services = device.getElementsByTagName("service");
                for (int s = 0; s < services.getLength(); s++) {
                    Element service = (Element) services.item(s);
                    String type = text(service, "serviceType");
                    String control = text(service, "controlURL");
                    if (type == null || control == null) {
                        continue;
                    }
                    if (transport == null && type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) {
                        transport = resolve(base, control);
                        name = text(device, "friendlyName");
                    } else if (rendering == null
                            && type.startsWith("urn:schemas-upnp-org:service:RenderingControl:")) {
                        rendering = resolve(base, control);
                    }
                }
            }
            if (transport == null) {
                return null;
            }
            if (name == null || name.isBlank()) {
                name = URI.create(location).getHost();
            }
            return new DlnaRenderer(name.trim(), location, transport, rendering);
        } catch (Exception e) {
            return null;
        }
    }

    /** The text of the first direct-or-nested element with that name, or null. */
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    static String resolve(String base, String path) {
        return URI.create(base).resolve(path.trim()).toString();
    }

    // ------------------------------------------------------------------ AVTransport

    /** Loads a stream: the renderer fetches {@code url} itself. Title and type go in the metadata. */
    public void load(String url, String title, String mimeType) throws IOException {
        invoke(avTransportUrl, AV_TRANSPORT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + escape(url) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + escape(didl(url, title, mimeType)) + "</CurrentURIMetaData>");
    }

    public void play() throws IOException {
        invoke(avTransportUrl, AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public void pause() throws IOException {
        invoke(avTransportUrl, AV_TRANSPORT, "Pause", "<InstanceID>0</InstanceID>");
    }

    public void stop() throws IOException {
        invoke(avTransportUrl, AV_TRANSPORT, "Stop", "<InstanceID>0</InstanceID>");
    }

    public void seek(int positionMs) throws IOException {
        invoke(avTransportUrl, AV_TRANSPORT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>"
                + "<Target>" + clock(positionMs) + "</Target>");
    }

    /** Position and length in ms; either is -1 when the renderer does not know it. */
    public int[] position() throws IOException {
        String reply = invoke(avTransportUrl, AV_TRANSPORT, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        return new int[]{parseClock(element(reply, "RelTime")), parseClock(element(reply, "TrackDuration"))};
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT, ... */
    public String state() throws IOException {
        String reply = invoke(avTransportUrl, AV_TRANSPORT, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        String state = element(reply, "CurrentTransportState");
        return state != null ? state.trim().toUpperCase(Locale.ROOT) : "";
    }

    /** Volume 0..100; renderers without RenderingControl ignore it. */
    public void setVolume(int percent) throws IOException {
        if (renderingControlUrl == null) {
            return;
        }
        invoke(renderingControlUrl, RENDERING_CONTROL, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>"
                        + Math.max(0, Math.min(100, percent)) + "</DesiredVolume>");
    }

    private String invoke(String url, String service, String action, String arguments) throws IOException {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + service + "\">" + arguments + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        Request request = new Request.Builder().url(url)
                .header("SOAPACTION", "\"" + service + "#" + action + "\"")
                .post(RequestBody.create(envelope, MediaType.get("text/xml; charset=utf-8")))
                .build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String error = element(body, "errorDescription");
                throw new IOException(Messages.format("dlna.action_failed", action, name,
                        error != null ? error : Messages.format("dlna.http_error", response.code())));
            }
            return body;
        }
    }

    /** DIDL-Lite metadata: most renderers show the title, and some refuse a stream without it. */
    static String didl(String url, String title, String mimeType) {
        String type = mimeType != null && !mimeType.isEmpty() ? mimeType : "audio/mpeg";
        String upnpClass = type.startsWith("video") ? "object.item.videoItem" : "object.item.audioItem.musicTrack";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + escape(title != null ? title : "")
                + "</dc:title><upnp:class>" + upnpClass + "</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + escape(type) + ":*\">" + escape(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** The text of an element in a SOAP reply, prefix or not, or null. */
    static String element(String xml, String tag) {
        Matcher matcher = Pattern.compile("<(?:\\w+:)?" + tag + "(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?" + tag + ">")
                .matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String clock(int ms) {
        int seconds = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** "1:02:03" or "01:02:03.500" to ms; -1 for "NOT_IMPLEMENTED" and the like. */
    static int parseClock(String text) {
        if (text == null) {
            return -1;
        }
        String[] parts = text.trim().split(":");
        if (parts.length != 3) {
            return -1;
        }
        try {
            double seconds = Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60
                    + Double.parseDouble(parts[2]);
            return (int) Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
