package de.danoeh.antennapod.net.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BasicAuthorizationInterceptorTest {
    private HttpServer first;
    private HttpServer second;
    private final AtomicReference<String> targetAuthorization = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        BasicAuthorizationInterceptor.setCredentialLookup(null);
        first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        first.start();
        second.start();
    }

    @After
    public void tearDown() {
        first.stop(0);
        second.stop(0);
        BasicAuthorizationInterceptor.setCredentialLookup(null);
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
                .addInterceptor(new BasicAuthorizationInterceptor())
                .build();
    }

    @Test
    public void testCredentialsAreNotReplayedToAnotherOrigin() throws Exception {
        String target = "http://127.0.0.1:" + second.getAddress().getPort() + "/target";
        first.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", target);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        second.createContext("/target", exchange -> {
            targetAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + first.getAddress().getPort() + "/start")
                .header("Authorization", "Basic original")
                .build();
        try (Response response = client().newCall(request).execute()) {
            assertEquals(401, response.code());
        }
        assertNull("credentials crossed origins", targetAuthorization.get());
    }

    @Test
    public void testCredentialsAreReplayedOnTheSameOrigin() throws Exception {
        second.createContext("/target", exchange -> {
            targetAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        second.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + second.getAddress().getPort() + "/start")
                .header("Authorization", "Basic original")
                .build();
        try (Response response = client().newCall(request).execute()) {
            assertEquals(401, response.code());
        }
        assertEquals("Basic original", targetAuthorization.get());
    }
}
