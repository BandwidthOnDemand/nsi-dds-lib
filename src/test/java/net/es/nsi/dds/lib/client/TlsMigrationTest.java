package net.es.nsi.dds.lib.client;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import net.es.nsi.dds.lib.dao.HttpsContext;
import net.es.nsi.dds.lib.dao.KeyStoreType;
import net.es.nsi.dds.lib.dao.SecureType;
import net.es.nsi.dds.lib.test.KeytoolFixture;
import net.es.nsi.dds.lib.test.SharedStateRule;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.glassfish.jersey.client.ClientProperties;
import static org.junit.Assert.*;

/** Exercises TLS trust, hostname modes, and context isolation with disposable stores and local HTTPS peers.
 * Tests serialize global state changes and restore providers, properties, and SSL contexts. */
public class TlsMigrationTest {
    @Rule
    public SharedStateRule serializeGlobalState = new SharedStateRule();

    @ClassRule
    public static TemporaryFolder temporary = new TemporaryFolder();
    private static File keyStore;
    private static File trusted;
    private static File unrelated;
    private HttpsServer server;
    private SSLContext previousDefault;
    private Object previousContext;
    private boolean previousProduction;
    private Provider[] previousProviders;
    private final Map<String, String> properties = new LinkedHashMap<>();

    @BeforeClass
    public static void stores() throws Exception {
        keyStore = KeytoolFixture.createKeyStore(temporary.getRoot(), "server");
        trusted = KeytoolFixture.createTrustStore(temporary.getRoot(), "trusted", keyStore);
        File otherKey = KeytoolFixture.createKeyStore(temporary.getRoot(), "other");
        unrelated = KeytoolFixture.createTrustStore(temporary.getRoot(), "unrelated", otherKey);
    }

    @Before
    public void start() throws Exception {
        previousProviders = Security.getProviders();
        previousDefault = SSLContext.getDefault();
        previousContext = field("sslContext").get(HttpsContext.getInstance());
        previousProduction = field("isProduction").getBoolean(HttpsContext.getInstance());
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(KeytoolFixture.load(keyStore, "PKCS12"), KeytoolFixture.PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @After
    public void restore() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        for (Map.Entry<String, String> property : properties.entrySet()) {
            if (property.getValue() == null) {
                System.clearProperty(property.getKey());
            } else {
                System.setProperty(property.getKey(), property.getValue());
            }
        }
        synchronized (HttpsContext.getInstance()) {
            field("sslContext").set(HttpsContext.getInstance(), previousContext);
            field("isProduction").setBoolean(HttpsContext.getInstance(), previousProduction);
            SSLContext.setDefault(previousDefault);
        }
        for (Provider provider : Security.getProviders()) {
            if (Arrays.stream(previousProviders).noneMatch(old -> old == provider)) {
                Security.removeProvider(provider.getName());
            }
        }
    }

    @Test(timeout = 10000)
    public void productionAcceptsTrustedCertificateForMatchingHostname() throws Exception {
        RestClient client = new RestClient(1, 1, configuration(true, trusted));
        try {
            try (Response response = client.get().target(url("localhost")).request().get()) {
                assertEquals(204, response.getStatus());
            }
        } finally {
            client.close();
        }
    }

    @Test(timeout = 10000)
    public void productionRejectsTrustedCertificateForWrongHostname() throws Exception {
        RestClient client = new RestClient(1, 1, configuration(true, trusted));
        try {
            assertTlsRejected(client, url("127.0.0.1"));
        } finally {
            client.close();
        }
    }

    @Test(timeout = 10000)
    public void productionRejectsUntrustedCertificate() throws Exception {
        RestClient client = new RestClient(1, 1, configuration(true, unrelated));
        try {
            // A literal loopback address avoids DNS fallback replacing the TLS error
            // with a final connection refusal on an unbound IPv6 address.
            assertTlsRejected(client, url("127.0.0.1"));
        } finally {
            client.close();
        }
    }

    @Test(timeout = 10000)
    public void nonproductionPermitsHostnameMismatchButStillRequiresTrust() throws Exception {
        RestClient permissive = new RestClient(1, 1, configuration(false, trusted));
        try {
            try (Response response = permissive.get().target(url("127.0.0.1")).request().get()) {
                assertEquals(204, response.getStatus());
            }
        } finally {
            permissive.close();
        }
        RestClient untrusted = new RestClient(1, 1, configuration(false, unrelated));
        try {
            assertTlsRejected(untrusted, url("127.0.0.1"));
        } finally {
            untrusted.close();
        }
    }

    @Test(timeout = 10000)
    public void jssePropertiesSupplyOmittedStoresAndTypes() throws Exception {
        setProperty("javax.net.ssl.keyStore", keyStore.toString());
        setProperty("javax.net.ssl.keyStorePassword", KeytoolFixture.PASSWORD);
        setProperty("javax.net.ssl.keyStoreType", "PKCS12");
        setProperty("javax.net.ssl.trustStore", trusted.toString());
        setProperty("javax.net.ssl.trustStorePassword", KeytoolFixture.PASSWORD);
        setProperty("javax.net.ssl.trustStoreType", "JKS");
        SecureType configuration = SecureType.builder().production(true).build();
        RestClient client = new RestClient(1, 1, configuration);
        try {
            try (Response response = client.get().target(url("localhost")).request().get()) {
                assertEquals(204, response.getStatus());
            }
            assertEquals("PKCS12", configuration.getKeyStore().getType());
            assertEquals("JKS", configuration.getTrustStore().getType());
        } finally {
            client.close();
        }
    }

    @Test(timeout = 10000)
    public void refusesHttpsDowngradeAndKeepsConnectionUsable() throws Exception {
        HttpServer plaintext = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger plaintextCalls = new AtomicInteger();
        plaintext.createContext("/target", exchange -> {
            plaintextCalls.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        plaintext.start();
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:"
                        + plaintext.getAddress().getPort() + "/target");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        RestClient client = null;
        try {
            client = new RestClient(1, 1, configuration(true, trusted));
            try (Response response = client.get().target(url("localhost").replace("/ping", "/redirect"))
                    .request().get()) {
                assertEquals(302, response.getStatus());
            }
            assertEquals("HTTPS request reached an unencrypted target", 0, plaintextCalls.get());
            try (Response response = client.get().target(url("localhost")).request().get()) {
                assertEquals(204, response.getStatus());
            }
        } finally {
            if (client != null) {
                client.close();
            }
            plaintext.stop(0);
        }
    }

    @Test(timeout = 15000)
    public void concurrentlyConstructedClientsKeepIndependentTrustContexts() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ConcurrentLinkedQueue<RestClient> ownedClients = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RestClient> accepting = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                RestClient client = new RestClient(1, 1, configuration(true, trusted));
                ownedClients.add(client);
                return client;
            });
            Future<RestClient> rejecting = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                RestClient client = new RestClient(1, 1, configuration(false, unrelated));
                ownedClients.add(client);
                return client;
            });
            assertTrue("Client constructors did not become ready", ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            RestClient trustedClient = accepting.get(5, TimeUnit.SECONDS);
            RestClient untrustedClient = rejecting.get(5, TimeUnit.SECONDS);
            try (Response response = trustedClient.get().target(url("localhost")).request().get()) {
                assertEquals(204, response.getStatus());
            }
            assertTlsRejected(untrustedClient, url("127.0.0.1"));
            // Loading the other singleton configuration must not alter this client.
            try (Response response = trustedClient.get().target(url("localhost")).request().get()) {
                assertEquals(204, response.getStatus());
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            try {
                assertTrue("TLS constructor workers did not stop", workers.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                ownedClients.forEach(RestClient::close);
            }
        }
    }

    @Test(timeout = 10000)
    public void invalidStorePasswordDoesNotReplacePreviouslyLoadedContext() throws Exception {
        RestClient valid = new RestClient(1, 1, configuration(true, trusted));
        try {
            SSLContext loaded = HttpsContext.getInstance().getSSLContext();
            SSLContext defaultBeforeFailure = SSLContext.getDefault();
            SecureType invalid = configuration(false, trusted);
            invalid.getKeyStore().setPassword("wrong-password");
            assertThrows(IOException.class, () -> new RestClient(1, 1, invalid));
            assertSame(loaded, HttpsContext.getInstance().getSSLContext());
            assertSame(defaultBeforeFailure, SSLContext.getDefault());
            assertTrue(HttpsContext.getInstance().isProduction());
            try (Response response = valid.get().target(url("localhost")).request().get()) {
                assertEquals(204, response.getStatus());
            }
        } finally {
            valid.close();
        }
    }

    @Test(timeout = 10000)
    public void bulkUnsubscribeNeverFollowsListOrDeleteHttpsDowngrades() throws Exception {
        HttpServer plaintext = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger plaintextCalls = new AtomicInteger();
        AtomicInteger secureDeletes = new AtomicInteger();
        AtomicInteger listRequests = new AtomicInteger();
        plaintext.createContext("/target", exchange -> {
            plaintextCalls.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        plaintext.start();
        String downgrade = "http://127.0.0.1:" + plaintext.getAddress().getPort() + "/target";
        server.createContext("/dds/subscriptions", exchange -> {
            try (exchange) {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    secureDeletes.incrementAndGet();
                    exchange.getResponseHeaders().set("Location", downgrade);
                    exchange.sendResponseHeaders(303, -1);
                } else if (listRequests.incrementAndGet() == 1) {
                    exchange.getResponseHeaders().set("Location", downgrade);
                    exchange.sendResponseHeaders(303, -1);
                } else {
                    byte[] body = ("<dds:subscriptions xmlns:dds=\"http://schemas.ogf.org/nsi/2014/02/discovery/types\">"
                            + "<dds:subscription id=\"old\" href=\"subscriptions/old\"/></dds:subscriptions>")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        DdsClient dds = null;
        try {
            dds = new DdsClient(1, 1, configuration(true, trusted));
            dds.get().property(ClientProperties.FOLLOW_REDIRECTS, true);
            String base = url("localhost").replace("/ping", "/dds");
            SubscriptionsResult redirectedList = dds.unsubscribe(base, "requester", "keep");
            assertEquals(Response.Status.BAD_REQUEST, redirectedList.getStatus());
            assertNull(redirectedList.getSubscriptions());
            assertEquals(0, secureDeletes.get());
            assertEquals(Response.Status.NO_CONTENT, dds.getPing(url("localhost").replace("/ping", "")).getStatus());
            SubscriptionsResult redirectedDelete = dds.unsubscribe(base, "requester", "keep");
            assertEquals(Response.Status.BAD_REQUEST, redirectedDelete.getStatus());
            assertEquals(1, redirectedDelete.getSubscriptions().size());
            assertEquals(1, secureDeletes.get());
            assertEquals(0, plaintextCalls.get());
            assertEquals(Boolean.TRUE, dds.get().getConfiguration().getProperty(ClientProperties.FOLLOW_REDIRECTS));
            assertEquals(Response.Status.NO_CONTENT, dds.getPing(url("localhost").replace("/ping", "")).getStatus());
        } finally {
            if (dds != null) {
                dds.close();
            }
            plaintext.stop(0);
        }
    }

    private static SecureType configuration(boolean production, File trust) {
        return SecureType.builder().production(production)
                .keyStore(KeyStoreType.builder().file(keyStore.toString()).password(KeytoolFixture.PASSWORD).type("PKCS12").build())
                .trustStore(KeyStoreType.builder().file(trust.toString()).password(KeytoolFixture.PASSWORD).type("JKS").build())
                .build();
    }

    private String url(String host) {
        return "https://" + host + ":" + server.getAddress().getPort() + "/ping";
    }

    private void setProperty(String name, String value) {
        if (!properties.containsKey(name)) {
            properties.put(name, System.getProperty(name));
        }
        System.setProperty(name, value);
    }

    private static Field field(String name) throws Exception {
        Field field = HttpsContext.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void assertTlsRejected(RestClient client, String url) {
        ProcessingException failure = assertThrows(ProcessingException.class, () -> {
            try (Response response = client.get().target(url).request().get()) {
                fail("TLS unexpectedly accepted request: " + response.getStatus());
            }
        });
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) {
                return;
            }
        }
        throw new AssertionError("Expected TLS failure rather than unrelated transport error", failure);
    }
}
