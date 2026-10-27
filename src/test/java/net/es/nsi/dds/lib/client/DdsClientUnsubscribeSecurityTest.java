package net.es.nsi.dds.lib.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises bulk-unsubscribe destination preflight, redirect suppression, and response cleanup using local peers. */
public class DdsClientUnsubscribeSecurityTest {
    private HttpServer server;
    private HttpServer other;
    private DdsClient client;
    private String base;
    private String otherBase;
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicInteger escaped = new AtomicInteger();
    private final AtomicReference<String> listing = new AtomicReference<>("");
    private final List<String> deletedPaths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> listedPaths = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger queriedDeletes = new AtomicInteger();

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                deletes.incrementAndGet();
                deletedPaths.add(exchange.getRequestURI().getRawPath());
                if (exchange.getRequestURI().getRawQuery() != null) {
                    queriedDeletes.incrementAndGet();
                }
                respond(exchange, 204, null);
            } else {
                listedPaths.add(exchange.getRequestURI().getRawPath());
                respond(exchange, 200, "<dds:subscriptions xmlns:dds=\"http://schemas.ogf.org/nsi/2014/02/discovery/types\">"
                        + listing.get() + "</dds:subscriptions>");
            }
        });
        server.createContext("/ping", exchange -> respond(exchange, 204, null));
        other.createContext("/", exchange -> {
            escaped.incrementAndGet();
            respond(exchange, 204, null);
        });
        server.start();
        other.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        otherBase = "http://127.0.0.1:" + other.getAddress().getPort();
        client = new DdsClient(1, 1);
    }

    @After
    public void stop() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (other != null) other.stop(0);
    }

    @Test(timeout = 10000)
    public void rejectsCrossOriginAndPreflightsEntireBatch() {
        listing.set(entry("valid", "subscriptions/valid") + entry("bad", otherBase + "/subscriptions/bad"));
        assertRejected();
        assertEquals(0, escaped.get());
    }

    @Test(timeout = 10000)
    public void rejectsSameOriginWrongScopeAndWrongIdentity() {
        for (String href : new String[]{base + "/documents/bad", base + "/subscriptions/keep", "/subscriptions/another"}) {
            listing.set(entry("bad", href));
            assertRejected();
        }
    }

    @Test(timeout = 10000)
    public void validPrefixedAndUnicodeEntriesUseOneConnectionAndKeepLegacyResult() {
        listing.set(entry("KEEP", "invalid-but-not-selected") + entry("réseau", "subscriptions/r%C3%A9seau")
                + entry("absolute", base + "/dds/subscriptions/absolute") + entry("relative", "/subscriptions/relative"));
        for (int i = 0; i < 3; i++) {
            SubscriptionsResult result = client.unsubscribe(base + "/dds/", "requester", "keep");
            assertEquals(Status.BAD_REQUEST, result.getStatus());
            assertEquals(3, result.getSubscriptions().size());
        }
        assertEquals(9, deletes.get());
        assertEquals(0, escaped.get());
        listing.set(entry("keep", "ignored"));
        assertTrue(client.unsubscribe(base, "requester", "KEEP").getSubscriptions().isEmpty());
    }

    @Test(timeout = 10000)
    public void disablesListAndDeleteRedirectsEvenWhenSharedClientEnablesThem() {
        client.get().property(ClientProperties.FOLLOW_REDIRECTS, true);
        server.createContext("/redirect/subscriptions", exchange -> {
            exchange.getResponseHeaders().set("Location", otherBase + "/escaped");
            respond(exchange, 303, null);
        });
        assertNull(client.unsubscribe(base + "/redirect", "requester", "keep").getSubscriptions());
        listing.set(entry("bad", "subscriptions/bad"));
        server.createContext("/subscriptions/bad", exchange -> {
            deletes.incrementAndGet();
            exchange.getResponseHeaders().set("Location", otherBase + "/escaped");
            respond(exchange, 303, null);
        });
        assertEquals(1, client.unsubscribe(base, "requester", "keep").getSubscriptions().size());
        assertEquals(1, deletes.get());
        assertEquals(0, escaped.get());
        assertEquals(Boolean.TRUE, client.get().getConfiguration().getProperty(ClientProperties.FOLLOW_REDIRECTS));
    }

    @Test(timeout = 10000)
    public void nullRetainedIdDoesNotSelectAllSubscriptions() {
        listing.set(entry("remove", "subscriptions/remove"));
        SubscriptionsResult result = client.unsubscribe(base, "requester", null);
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertNull(result.getSubscriptions());
        assertEquals(0, deletes.get());
    }

    @Test(timeout = 10000)
    public void directUrlApisRetainCallerSelectedTargets() {
        assertEquals(Status.NO_CONTENT, client.unsubscribe(base, otherBase + "/chosen").getStatus());
        assertEquals(Status.NO_CONTENT, client.delete(otherBase + "/chosen").getStatus());
        assertEquals(2, escaped.get());
    }

    @Test(timeout = 10000)
    public void rejectsLateMalformedEntriesBeforeAnyDeleteAndReleasesListResponse() {
        String[] malformedEntries = {"<dds:subscription/>", "<dds:subscription href=\"subscriptions/a\"/>",
            "<dds:subscription id=\"a\"/>",
            entry("", "subscriptions/empty"), entry("a", ""), entry("a", "subscriptions/%ED%A0%80"),
            entry("a", "subscriptions/%2561"), entry("a", "subscriptions/a?query"),
            entry("a", "subscriptions/a#fragment"), entry("a", "subscriptions/a/other"),
            entry("a", "subscriptions/keep"), entry("a", "subscriptions/A"),
            "<dds:subscription xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:nil=\"true\"/>"};
        for (String malformed : malformedEntries) {
            listing.set(entry("valid", "subscriptions/valid") + malformed);
            assertRejected();
            assertConnectionUsable();
        }
        assertEquals(0, escaped.get());
    }

    @Test(timeout = 10000)
    public void dispatchesCanonicalEncodedTargetsAndKeepsProcessedOrder() {
        listing.set(entry("KEEP", "ignored") + entry("a", "subscriptions/%61")
                + entry("réseau", "subscriptions/r%C3%A9seau")
                + entry("東京", base + "/dds/subscriptions/%E6%9D%B1%E4%BA%AC"));
        SubscriptionsResult result = client.unsubscribe(base + "/dds", "requester", "keep");
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertEquals(List.of("a", "réseau", "東京"),
                result.getSubscriptions().stream().map(subscription -> subscription.getId()).toList());
        assertEquals("Canonical targets must not acquire a query on the wire", 0, queriedDeletes.get());
        assertFalse("Bulk deletion reached the subscriptions collection",
                deletedPaths.stream().anyMatch(path -> path.equals("/dds/subscriptions") || path.equals("/dds/subscriptions/")));
        assertEquals(List.of("/dds/subscriptions/a", "/dds/subscriptions/r%C3%A9seau",
                "/dds/subscriptions/%E6%9D%B1%E4%BA%AC"), deletedPaths);
        assertConnectionUsable();
    }

    @Test(timeout = 10000)
    public void canonicalizesUnicodeServicePrefixOnListAndDeleteRequests() {
        listing.set(entry("a", "subscriptions/a"));
        SubscriptionsResult result = client.unsubscribe(base + "/réseau", "requester", "keep");
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertEquals(1, result.getSubscriptions().size());
        assertEquals(List.of("/r%C3%A9seau/subscriptions"), listedPaths);
        assertEquals(List.of("/r%C3%A9seau/subscriptions/a"), deletedPaths);
        assertConnectionUsable();
    }

    @Test(timeout = 10000)
    public void selectedDecomposedIdNeverDeletesRetainedComposedId() {
        listing.set(entry("\u00e9", "subscriptions/%C3%A9")
                + entry("e\u0301", "subscriptions/e%CC%81"));
        SubscriptionsResult result = client.unsubscribe(base, "requester", "\u00e9");
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertEquals(List.of("e\u0301"),
                result.getSubscriptions().stream().map(subscription -> subscription.getId()).toList());
        assertFalse("Canonicalization redirected DELETE onto the retained NFC identity",
                deletedPaths.contains("/subscriptions/%C3%A9"));
        assertEquals(List.of("/subscriptions/e%CC%81"), deletedPaths);
        assertEquals(0, queriedDeletes.get());
        assertConnectionUsable();
    }

    @Test(timeout = 10000)
    public void listAndDeleteKeepExactDecomposedServicePrefixOnWire() {
        listing.set(entry("a", "subscriptions/a"));
        SubscriptionsResult result = client.unsubscribe(base + "/cafe\u0301", "requester", "keep");
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertEquals(1, result.getSubscriptions().size());
        assertEquals(List.of("/cafe%CC%81/subscriptions"), listedPaths);
        assertEquals(List.of("/cafe%CC%81/subscriptions/a"), deletedPaths);
        assertConnectionUsable();
    }

    @Test(timeout = 10000)
    public void handlesListErrorsMalformedAndEmptyBodiesWithoutLeakingSingleConnection() {
        server.createContext("/error/subscriptions", exchange -> respond(exchange, 404, error(404)));
        server.createContext("/malformed/subscriptions", exchange -> respond(exchange, 200, "<dds:subscriptions"));
        server.createContext("/empty/subscriptions", exchange -> respond(exchange, 200, null));
        for (String prefix : new String[]{"error", "malformed", "empty"}) {
            SubscriptionsResult result = client.unsubscribe(base + "/" + prefix, "requester", "keep");
            assertEquals(Status.BAD_REQUEST, result.getStatus());
            assertNull(result.getSubscriptions());
            assertConnectionUsable();
        }
        assertEquals(0, deletes.get());
    }

    @Test(timeout = 10000)
    public void deletionErrorsDoNotChangeLegacyProcessedListOrLeakSingleConnection() {
        server.createContext("/subscriptions/error", exchange -> {
            deletes.incrementAndGet();
            respond(exchange, 403, error(403));
        });
        server.createContext("/subscriptions/malformed", exchange -> {
            deletes.incrementAndGet();
            respond(exchange, 400, "<invalid");
        });
        listing.set(entry("error", "subscriptions/error") + entry("malformed", "subscriptions/malformed")
                + entry("ok", "subscriptions/ok"));
        SubscriptionsResult result = client.unsubscribe(base, "requester", "keep");
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertEquals(List.of("error", "malformed", "ok"),
                result.getSubscriptions().stream().map(subscription -> subscription.getId()).toList());
        assertEquals(3, deletes.get());
        assertConnectionUsable();
    }

    @Test(timeout = 10000)
    public void suppressesSameOriginListAndDeleteRedirectsWithoutChangingDirectApiPolicy() {
        client.get().property(ClientProperties.FOLLOW_REDIRECTS, true);
        server.createContext("/redirected-target", exchange -> {
            escaped.incrementAndGet();
            respond(exchange, 204, null);
        });
        server.createContext("/redirect/subscriptions", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected-target");
            respond(exchange, 303, null);
        });
        assertNull(client.unsubscribe(base + "/redirect", "requester", "keep").getSubscriptions());
        assertConnectionUsable();
        server.createContext("/subscriptions/redirect", exchange -> {
            deletes.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/redirected-target");
            respond(exchange, 303, null);
        });
        listing.set(entry("redirect", "subscriptions/redirect"));
        assertEquals(1, client.unsubscribe(base, "requester", "keep").getSubscriptions().size());
        assertEquals(0, escaped.get());
        assertConnectionUsable();
        assertEquals(Status.NO_CONTENT, client.unsubscribe(base, "subscriptions/redirect").getStatus());
        assertEquals(1, escaped.get());
        assertEquals(Boolean.TRUE, client.get().getConfiguration().getProperty(ClientProperties.FOLLOW_REDIRECTS));
    }

    private void assertRejected() {
        SubscriptionsResult result = client.unsubscribe(base, "requester", "keep");
        assertEquals(Status.BAD_REQUEST, result.getStatus());
        assertNull(result.getSubscriptions());
        assertEquals(0, deletes.get());
    }

    private static String entry(String id, String href) {
        return "<dds:subscription id=\"" + id + "\" href=\"" + href + "\"/>";
    }

    private void assertConnectionUsable() {
        assertEquals(Status.NO_CONTENT, client.getPing(base).getStatus());
    }

    private static String error(int status) {
        return "<dds:error xmlns:dds=\"http://schemas.ogf.org/nsi/2014/02/discovery/types\" "
                + "id=\"fixture\" date=\"2026-10-27T00:00:00Z\"><code>" + status
                + "</code><label>rejected</label><description>fixture</description><resource>urn:test</resource></dds:error>";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=UTF-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
