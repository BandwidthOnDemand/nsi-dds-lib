package net.es.nsi.dds.lib.client;

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises redirect chains, Jakarta entity providers, and pooled-response cleanup against a local HTTP server. */
public class RestClientMigrationTest {
    private HttpServer server;
    private String base;
    private RestClient client;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new RestClient(1, 1);
    }

    @After
    public void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test(timeout = 10000)
    public void followsRelativeGetRedirect() {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            byte[] body = "complete".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        try (Response response = client.get().target(base + "/redirect").request().get()) {
            assertEquals(200, response.getStatus());
            assertEquals("complete", response.readEntity(String.class));
        }
    }

    @Test(timeout = 10000)
    public void followsPermanentGetRedirect() {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try (Response response = client.get().target(base + "/redirect").request().get()) {
            assertEquals(204, response.getStatus());
        }
    }

    @Test(timeout = 10000)
    public void doesNotReplayPostOn302() {
        AtomicInteger targetCalls = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try (Response response = client.get().target(base + "/redirect").request()
                .post(Entity.text("payload"))) {
            assertEquals(302, response.getStatus());
            assertEquals(0, targetCalls.get());
        }
    }

    @Test(timeout = 10000)
    public void delete303ContinuesThroughGetRedirectChain() throws Exception {
        assertRedirectChain("DELETE");
    }

    @Test(timeout = 10000)
    public void bufferedPost303ContinuesThroughGetRedirectChain() throws Exception {
        assertRedirectChain("POST");
    }

    private void assertRedirectChain(String method) throws Exception {
        List<String> methods = new CopyOnWriteArrayList<>();
        String[] paths = {"/start", "/middle", "/next", "/target"};
        int[] statuses = {303, 302, 307, 204};
        for (int index = 0; index < paths.length; index++) {
            final int hop = index;
            server.createContext(paths[index], exchange -> {
                methods.add(exchange.getRequestMethod());
                exchange.getRequestBody().readAllBytes();
                try (exchange) {
                    if (hop < paths.length - 1) {
                        exchange.getResponseHeaders().set("Location", paths[hop + 1]);
                        byte[] body = "redirect body".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(statuses[hop], body.length);
                        exchange.getResponseBody().write(body);
                    } else {
                        exchange.sendResponseHeaders(statuses[hop], -1);
                    }
                }
            });
        }
        if ("POST".equals(method)) {
            try (Response response = client.get().target(base + "/start")
                    .property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED)
                    .request().post(Entity.text("payload"))) {
                assertEquals(204, response.getStatus());
            }
        } else {
            try (Response response = client.get().target(base + "/start").request().delete()) {
                assertEquals(204, response.getStatus());
            }
        }
        assertEquals(List.of(method, "GET", "GET", "GET"), methods);
        // A new request must acquire the sole pooled connection after all hops close.
        try (Response response = client.get().target(base + "/target").request().get()) {
            assertEquals(204, response.getStatus());
        }
        assertEquals(List.of(method, "GET", "GET", "GET", "GET"), methods);
    }

    @Test(timeout = 10000)
    public void readsDdsXmlEntityWithJakartaProviders() throws Exception {
        server.createContext("/documents", exchange -> {
            byte[] body = "<documents xmlns=\"http://schemas.ogf.org/nsi/2014/02/discovery/types\"/>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        DdsClient dds = new DdsClient(1, 1);
        try {
            DocumentsResult result = dds.getDocuments(base);
            assertEquals(Response.Status.OK, result.getStatus());
            assertTrue(result.getDocuments().isEmpty());
        } finally {
            dds.close();
        }
    }

    @Test(timeout = 10000)
    public void malformedXmlDoesNotExhaustSingleConnectionPool() throws Exception {
        server.createContext("/documents", exchange -> {
            byte[] body = "<invalid".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        DdsClient dds = new DdsClient(1, 1);
        try {
            assertEquals(Response.Status.INTERNAL_SERVER_ERROR, dds.getDocuments(base).getStatus());
            assertEquals(Response.Status.NO_CONTENT, dds.getPing(base).getStatus());
        } finally {
            dds.close();
        }
    }

    @Test(timeout = 10000)
    public void circularRedirectFailsWithoutExhaustingSingleConnectionPool() {
        AtomicInteger redirects = new AtomicInteger();
        server.createContext("/first", exchange -> {
            redirects.incrementAndGet();
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/second");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/second", exchange -> {
            redirects.incrementAndGet();
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/first");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/ping", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        ProcessingException failure = assertThrows(ProcessingException.class, () -> {
            try (Response ignored = client.get().target(base + "/first").request().get()) {
                fail("Circular redirect unexpectedly completed");
            }
        });
        boolean circular = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            circular |= cause.getClass().getSimpleName().equals("CircularRedirectException");
        }
        assertTrue("Failure was unrelated to redirect-loop detection", circular);
        assertTrue("Repeated loop was not stopped promptly", redirects.get() >= 2 && redirects.get() <= 4);
        try (Response response = client.get().target(base + "/ping").request().get()) {
            assertEquals(204, response.getStatus());
        }
    }
}
