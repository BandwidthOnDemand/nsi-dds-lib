package net.es.nsi.dds.lib.client;

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.es.nsi.dds.lib.test.SharedStateRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks socket and pool-acquisition timeouts; the shared-state rule restores timeout properties. */
public class RestClientTimeoutTest {
    @Rule
    public SharedStateRule sharedState = new SharedStateRule(
            "tcpConnectTimeout", "tcpSoTimeout", "tcpConnectRequestTimeout");

    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private RestClient client;
    private String base;

    @Before
    public void start() throws Exception {
        System.setProperty("tcpConnectTimeout", "1000");
        System.setProperty("tcpSoTimeout", "250");
        System.setProperty("tcpConnectRequestTimeout", "250");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/ping", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new RestClient(1, 1);
    }

    @After
    public void stop() throws Exception {
        release.countDown();
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
        assertTrue("HTTP fixture did not stop", executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test(timeout = 10000)
    public void readTimeoutDoesNotRetryOrExhaustSingleConnectionPool() {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/delayed", exchange -> {
            attempts.incrementAndGet();
            received.countDown();
            try (exchange) {
                awaitRelease();
                exchange.sendResponseHeaders(204, -1);
            } catch (IOException closedByTimeout) {
                // The client intentionally closes its timed-out socket.
            }
        });
        ProcessingException failure = assertThrows(ProcessingException.class, () -> {
            try (Response ignored = client.get().target(base + "/delayed").request().get()) {
                fail("Response timeout was not enforced");
            }
        });
        assertCause(failure, "SocketTimeoutException");
        assertEquals(0, received.getCount());
        assertEquals(1, attempts.get());
        release.countDown();
        assertPing();
    }

    @Test(timeout = 10000)
    public void poolAcquisitionTimeoutRecoversAfterCallerClosesHeldResponse() throws Exception {
        server.createContext("/held", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 16);
                exchange.getResponseBody().write(new byte[4]);
                exchange.getResponseBody().flush();
                awaitRelease();
                exchange.getResponseBody().write(new byte[12]);
            } catch (IOException closedByClient) {
                // Closing an unread entity can close the fixture socket.
            }
        });
        try (Response held = client.get().target(base + "/held").request().get()) {
            assertEquals(200, held.getStatus());
            ProcessingException failure = assertThrows(ProcessingException.class, () -> {
                try (Response ignored = client.get().target(base + "/ping").request().get()) {
                    fail("A second response acquired the sole leased connection");
                }
            });
            assertCause(failure, "ConnectionRequestTimeoutException");
            release.countDown();
        }
        assertPing();
    }

    private void assertPing() {
        try (Response response = client.get().target(base + "/ping").request().get()) {
            assertEquals(204, response.getStatus());
        }
    }

    private void awaitRelease() {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Fixture release was not signalled");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertCause(Throwable failure, String simpleName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals(simpleName)) {
                return;
            }
        }
        throw new AssertionError("Expected " + simpleName + " rather than an unrelated failure", failure);
    }
}
