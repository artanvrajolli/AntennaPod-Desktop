package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Finds UPnP media renderers on the local network: an SSDP M-SEARCH goes out on every IPv4
 * interface that is up (a laptop with a VPN or virtual adapters would otherwise ask only one),
 * and each answer's LOCATION is read as a device description.
 */
public final class DlnaDiscovery {
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private DlnaDiscovery() {
    }

    /** Renderers that answered within {@code timeoutMs}, each once, in the order they answered. */
    public static List<DlnaRenderer> discover(int timeoutMs) throws IOException {
        Set<String> locations = new LinkedHashSet<>();
        String search = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + GROUP + ":" + PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + SEARCH_TARGET + "\r\n"
                + "USER-AGENT: Windows/10 UPnP/1.1 AntennaPod-Desktop/1\r\n\r\n";
        byte[] payload = search.getBytes(StandardCharsets.US_ASCII);
        List<MulticastSocket> sockets = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address.getAddress().length != 4) {
                        continue;
                    }
                    try {
                        MulticastSocket socket = new MulticastSocket(new InetSocketAddress(address, 0));
                        socket.setNetworkInterface(nif);
                        socket.setTimeToLive(2);
                        socket.setSoTimeout(200);
                        sockets.add(socket);
                        DatagramPacket packet = new DatagramPacket(payload, payload.length,
                                InetAddress.getByName(GROUP), PORT);
                        // twice: SSDP is UDP, and one lost datagram would hide a device
                        socket.send(packet);
                        socket.send(packet);
                    } catch (IOException e) {
                        // an interface that cannot send multicast is simply not searched
                    }
                }
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[4096];
            while (System.currentTimeMillis() < deadline && !sockets.isEmpty()) {
                for (MulticastSocket socket : sockets) {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(reply);
                        String location = locationOf(new String(reply.getData(), 0, reply.getLength(),
                                StandardCharsets.UTF_8));
                        if (location != null) {
                            locations.add(location);
                        }
                    } catch (SocketTimeoutException e) {
                        // nothing on this interface yet
                    }
                }
            }
        } finally {
            for (MulticastSocket socket : sockets) {
                socket.close();
            }
        }
        List<DlnaRenderer> renderers = new ArrayList<>();
        for (String location : locations) {
            DlnaRenderer renderer = describe(location);
            if (renderer != null && !renderers.contains(renderer)) {
                renderers.add(renderer);
            }
        }
        return renderers;
    }

    /** Reads a device description; null when it is unreachable or no renderer. */
    public static DlnaRenderer describe(String location) {
        Request request = new Request.Builder().url(location).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return DlnaRenderer.fromDescription(location, response.body().string());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** The LOCATION of an SSDP answer that is a media renderer, else null. */
    static String locationOf(String answer) {
        String location = null;
        boolean renderer = false;
        for (String line : answer.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String header = line.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (header.equals("LOCATION")) {
                location = value;
            } else if ((header.equals("ST") || header.equals("NT"))
                    && value.toLowerCase(Locale.ROOT).startsWith("urn:schemas-upnp-org:device:mediarenderer:")) {
                renderer = true;
            }
        }
        return renderer && location != null && location.startsWith("http") ? location : null;
    }
}
