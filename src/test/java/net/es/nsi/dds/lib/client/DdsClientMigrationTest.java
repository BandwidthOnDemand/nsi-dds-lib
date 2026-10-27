package net.es.nsi.dds.lib.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.es.nsi.common.constants.Nsi;
import net.es.nsi.dds.lib.jaxb.DdsParser;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionRequestType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises DDS status, entity, encoding, and conditional-request contracts with a local HTTP peer. */
public class DdsClientMigrationTest {
    private static final String NS = "http://schemas.ogf.org/nsi/2014/02/discovery/types";
    private static final String HTTP_DATE = "Mon, 26 Oct 2026 12:34:56 GMT";
    private HttpServer server;
    private DdsClient client;
    private String base;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new DdsClient(1, 1);
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
    public void documentGetPreservesPayloadAcceptAndLastModified() {
        AtomicReference<String> accept = new AtomicReference<>();
        server.createContext("/documents/nsa/type/id", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().set("Last-Modified", HTTP_DATE);
            respond(exchange, 200, root("document", " id=\"id\" version=\"2026-10-26T12:34:56Z\" expires=\"2027-01-01T00:00:00Z\"",
                    "<nsa>urn:nsa:test</nsa><type>réseau</type>"));
        });
        DocumentResult result = client.getDocument(base, "nsa", "type", "id");
        assertEquals(Response.Status.OK, result.getStatus());
        assertEquals("réseau", result.getDocument().getType());
        assertEquals(Instant.parse("2026-10-26T12:34:56Z").toEpochMilli(), result.getLastModified());
        assertEquals(Nsi.NSI_DDS_V1_XML, accept.get());
    }

    @Test(timeout = 10000)
    public void conditionalSubscriptionUsesHttpDateAndKeeps304WithoutEntity() {
        AtomicReference<String> conditional = new AtomicReference<>();
        server.createContext("/subscriptions/s", exchange -> {
            conditional.set(exchange.getRequestHeaders().getFirst("If-Modified-Since"));
            respond(exchange, 304, null);
        });
        long modified = Instant.parse("2026-10-26T12:34:56.789Z").toEpochMilli();
        SubscriptionResult result = client.getSubscription(base, "subscriptions/s", new Date(modified));
        assertEquals(Response.Status.NOT_MODIFIED, result.getStatus());
        assertNull(result.getSubscription());
        assertEquals(modified / 1000 * 1000, result.getLastModified());
        assertEquals(HTTP_DATE, conditional.get());
    }

    @Test(timeout = 10000)
    public void readsGzippedXmlResponse() {
        server.createContext("/documents", exchange -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(root("documents", "", "").getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            byte[] body = bytes.toByteArray();
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        DocumentsResult result = client.getDocuments(base);
        assertEquals(Response.Status.OK, result.getStatus());
        assertTrue(result.getDocuments().isEmpty());
    }

    @Test(timeout = 10000)
    public void subscribeSendsChunkedGzipAndReadsCreatedEntity() throws Exception {
        AtomicReference<String> encoding = new AtomicReference<>();
        AtomicReference<String> transfer = new AtomicReference<>();
        AtomicReference<byte[]> request = new AtomicReference<>();
        server.createContext("/subscriptions", exchange -> {
            encoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            transfer.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            request.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Last-Modified", HTTP_DATE);
            respond(exchange, 201, root("subscription", " id=\"s\" href=\"/subscriptions/s\" version=\"2026-10-26T12:34:56Z\"",
                    "<requesterId>urn:nsa:test</requesterId><callback>https://localhost/callback</callback>"));
        });
        SubscriptionResult result = client.subscribe(base, "urn:nsa:test", "https://localhost/callback");
        assertEquals(Response.Status.CREATED, result.getStatus());
        assertEquals("s", result.getSubscription().getId());
        assertEquals("gzip", encoding.get());
        assertEquals("chunked", transfer.get());
        String decoded;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(request.get()))) {
            decoded = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
        SubscriptionRequestType parsed = DdsParser.getInstance().xml2Jaxb(SubscriptionRequestType.class, decoded);
        assertEquals("urn:nsa:test", parsed.getRequesterId());
        assertEquals("https://localhost/callback", parsed.getCallback());
    }

    @Test(timeout = 10000)
    public void errorEntityAndDeleteNoContentKeepRemoteStatusesAndReleaseConnection() {
        server.createContext("/documents", exchange -> respond(exchange, 404,
                root("error", " id=\"error\" date=\"2026-10-26T12:34:56Z\"",
                        "<code>404</code><label>Missing</label><description>Absent</description><resource>urn:test</resource>")));
        server.createContext("/subscriptions/s", exchange -> respond(exchange, 204, null));
        assertEquals(Response.Status.NOT_FOUND, client.getDocuments(base).getStatus());
        assertEquals(Response.Status.NO_CONTENT, client.unsubscribe(base, "subscriptions/s").getStatus());
        assertEquals(Response.Status.NO_CONTENT, client.delete(base + "/subscriptions/s").getStatus());
    }

    @Test(timeout = 10000)
    public void emptySuccessfulXmlResponseBecomesFailureAndReleasesConnection() {
        server.createContext("/documents", exchange -> respond(exchange, 200, null));
        server.createContext("/ping", exchange -> respond(exchange, 204, null));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, client.getDocuments(base).getStatus());
        assertEquals(Response.Status.NO_CONTENT, client.getPing(base).getStatus());
    }

    @Test(timeout = 10000)
    public void doesNotHideRemote503WithAutomaticResponseRetry() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/ping", exchange -> respond(exchange, attempts.incrementAndGet() == 1 ? 503 : 204, null));
        assertEquals(Response.Status.SERVICE_UNAVAILABLE, client.getPing(base).getStatus());
        assertEquals(1, attempts.get());
    }

    @Test(timeout = 10000)
    public void doesNotHideRemote429WithRetryAfter() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/ping", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, attempts.incrementAndGet() == 1 ? 429 : 204, null);
        });
        try (Response response = client.get().target(base + "/ping").request().get()) {
            assertEquals(429, response.getStatus());
            assertEquals("1", response.getHeaderString("Retry-After"));
        }
        assertEquals(1, attempts.get());
    }

    @Test(timeout = 10000)
    public void doesNotReplayDeleteOnTemporaryRedirect() {
        AtomicInteger targetCalls = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            respond(exchange, 307, null);
        });
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 204, null);
        });
        try (Response response = client.get().target(base + "/redirect").request().delete()) {
            assertEquals(307, response.getStatus());
        }
        assertEquals(0, targetCalls.get());
    }

    @Test(timeout = 10000)
    public void crossOriginRedirectDoesNotForwardSensitiveHeaders() throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetCalls = new AtomicInteger();
        target.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 204, null);
        });
        target.start();
        try {
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/target");
                respond(exchange, 302, null);
            });
            for (String header : new String[]{"Authorization", "Cookie"}) {
                try (Response response = client.get().target(base + "/redirect").request()
                        .header(header, "fixture-value").get()) {
                    assertEquals(header, 302, response.getStatus());
                }
            }
            assertEquals(0, targetCalls.get());
        } finally {
            target.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String xml) throws IOException {
        try (exchange) {
            if (xml == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=UTF-8");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        }
    }

    private static String root(String local, String attributes, String content) {
        return "<dds:" + local + " xmlns:dds=\"" + NS + "\"" + attributes + ">" + content + "</dds:" + local + ">";
    }
}
