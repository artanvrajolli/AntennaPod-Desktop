package de.danoeh.antennapod.desktop;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Serves the episode being cast to the renderer, which fetches it over plain HTTP on the local
 * network. A downloaded or local file is served from disk; a streamed episode is relayed, which
 * also covers https-only feeds (many renderers speak only http) and password-protected ones (the
 * login is added here, by the app's own HTTP client). Byte ranges are honoured so the renderer
 * can seek.
 *
 * <p>Only media handed to {@link #publish} is reachable, each under an unguessable token; any
 * other path answers 404, so nothing else on disk is exposed to the network.
 */
public final class CastServer implements AutoCloseable {
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final HttpServer server;
    private final Map<String, Source> published = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private static final class Source {
        final File file;
        final String remoteUrl;
        final String mimeType;

        Source(File file, String remoteUrl, String mimeType) {
            this.file = file;
            this.remoteUrl = remoteUrl;
            this.mimeType = mimeType != null && !mimeType.isEmpty() ? mimeType : "audio/mpeg";
        }
    }

    public CastServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/media/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "cast-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Makes a file, or else a remote URL, reachable for a renderer at {@code rendererHost} and
     * returns the address to hand it. Only the latest publication stays reachable.
     */
    public String publish(File file, String remoteUrl, String mimeType, String rendererHost) throws IOException {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) {
            token.append(String.format("%02x", b));
        }
        published.clear();
        published.put(token.toString(), new Source(file != null && file.isFile() ? file : null, remoteUrl, mimeType));
        return "http://" + localAddressFor(rendererHost).getHostAddress() + ":" + port()
                + "/media/" + token + extensionFor(mimeType);
    }

    public void unpublishAll() {
        published.clear();
    }

    /** The address of this computer on the network the renderer is on. */
    static InetAddress localAddressFor(String host) throws IOException {
        try (DatagramSocket probe = new DatagramSocket()) {
            // connecting a UDP socket sends nothing; it only picks the route, and so the interface
            probe.connect(InetAddress.getByName(host), 1900);
            InetAddress local = probe.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) {
                throw new IOException(Messages.format("cast.no_route", host));
            }
            return local;
        }
    }

    /** Some renderers pick the decoder by the URL's extension. */
    static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return ".mp3";
        }
        switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/mp4":
            case "audio/x-m4a":
            case "audio/aac":
                return ".m4a";
            case "video/mp4":
                return ".mp4";
            case "audio/wav":
            case "audio/x-wav":
                return ".wav";
            case "audio/ogg":
                return ".ogg";
            default:
                return ".mp3";
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String token = path.substring("/media/".length());
            int dot = token.indexOf('.');
            if (dot >= 0) {
                token = token.substring(0, dot);
            }
            Source source = published.get(token);
            boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            if (source == null || !(head || "GET".equalsIgnoreCase(exchange.getRequestMethod()))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Headers out = exchange.getResponseHeaders();
            out.add("Content-Type", source.mimeType);
            out.add("Accept-Ranges", "bytes");
            // what DLNA renderers (Samsung and LG TVs among them) look for before they play
            out.add("transferMode.dlna.org", "Streaming");
            out.add("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (source.file != null) {
                serveFile(exchange, source.file, range, head);
            } else {
                relay(exchange, source.remoteUrl, range, head);
            }
        } finally {
            exchange.close();
        }
    }

    private static void serveFile(HttpExchange exchange, File file, String range, boolean head) throws IOException {
        long length = file.length();
        long[] span = span(range, length);
        if (span == null) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + length);
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        long count = span[1] - span[0] + 1;
        if (range != null) {
            exchange.getResponseHeaders().add("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + length);
        }
        exchange.sendResponseHeaders(range != null ? 206 : 200, head ? -1 : (count == 0 ? -1 : count));
        if (head || count == 0) {
            return;
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r"); OutputStream body = exchange.getResponseBody()) {
            in.seek(span[0]);
            byte[] buffer = new byte[64 * 1024];
            long left = count;
            while (left > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    break;
                }
                body.write(buffer, 0, read);
                left -= read;
            }
        } catch (IOException e) {
            // the renderer hung up mid-transfer (it does, when seeking); nothing to report
        }
    }

    /** First and last byte of a Range over {@code length} bytes; the whole file without one; null if unsatisfiable. */
    static long[] span(String range, long length) {
        if (range == null) {
            return new long[]{0, length - 1};
        }
        Matcher matcher = RANGE.matcher(range.trim());
        if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            return new long[]{0, length - 1};
        }
        long first;
        long last;
        if (matcher.group(1).isEmpty()) {
            // "bytes=-500": the last 500 bytes
            long suffix = Long.parseLong(matcher.group(2));
            first = Math.max(0, length - suffix);
            last = length - 1;
        } else {
            first = Long.parseLong(matcher.group(1));
            last = matcher.group(2).isEmpty() ? length - 1 : Math.min(Long.parseLong(matcher.group(2)), length - 1);
        }
        return first >= length || first > last ? null : new long[]{first, last};
    }

    private static void relay(HttpExchange exchange, String url, String range, boolean head) throws IOException {
        Request.Builder request = new Request.Builder().url(url);
        if (range != null) {
            request.header("Range", range);
        }
        request.method(head ? "HEAD" : "GET", null);
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request.build()).execute()) {
            String contentRange = response.header("Content-Range");
            if (contentRange != null) {
                exchange.getResponseHeaders().add("Content-Range", contentRange);
            }
            ResponseBody body = response.body();
            long length = body != null ? body.contentLength() : -1;
            int status = response.code() == 206 ? 206 : response.isSuccessful() ? 200 : response.code();
            if (head || body == null || !response.isSuccessful()) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.sendResponseHeaders(status, length > 0 ? length : 0);
            try (InputStream in = body.byteStream(); OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            } catch (IOException e) {
                // the renderer hung up (seeking, stopping); the upstream call is closed with the response
            }
        }
    }

    @Override
    public void close() {
        published.clear();
        server.stop(0);
    }
}
