package net.es.nsi.dds.lib.client;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks actual request replay against a bounded local TCP peer that resets accepted connections. */
public class RestClientIoRetryTest {
    @Test(timeout = 15000)
    public void getRecoversAfterThreeActualConnectionResets() throws Exception {
        try (ResettingPeer peer = new ResettingPeer(3)) {
            RestClient client = new RestClient(1, 1);
            try {
                try (Response response = client.get().target(peer.url()).request().get()) {
                    assertEquals(204, response.getStatus());
                }
                assertEquals(List.of("GET", "GET", "GET", "GET"), peer.methods);
            } finally {
                client.close();
            }
        }
    }

    @Test(timeout = 15000)
    public void repeatedConnectionResetsStopAfterFourAttempts() throws Exception {
        try (ResettingPeer peer = new ResettingPeer(Integer.MAX_VALUE)) {
            RestClient client = new RestClient(1, 1);
            try {
                assertThrows(ProcessingException.class, () -> {
                    try (Response ignored = client.get().target(peer.url()).request().get()) {
                        fail("Expected the exhausted I/O retry budget to fail");
                    }
                });
                assertEquals(List.of("GET", "GET", "GET", "GET"), peer.methods);
            } finally {
                client.close();
            }
        }
    }

    @Test(timeout = 15000)
    public void bufferedPostIsNotReplayedAfterServerReceivesBody() throws Exception {
        assertBodyNotReplayed("POST");
    }

    @Test(timeout = 15000)
    public void repeatablePutIsNotReplayedAfterServerReceivesBody() throws Exception {
        assertBodyNotReplayed("PUT");
    }

    private static void assertBodyNotReplayed(String method) throws Exception {
        try (ResettingPeer peer = new ResettingPeer(1)) {
            RestClient client = new RestClient(1, 1);
            try {
                assertThrows(ProcessingException.class, () -> {
                    try (Response ignored = client.get().target(peer.url())
                            .property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED)
                            .request().method(method, Entity.text("received before reset"))) {
                        fail("A request with an entity was replayed after an ambiguous failure");
                    }
                });
                assertEquals(List.of(method), peer.methods);
                assertEquals(List.of("received before reset"), peer.bodies);
            } finally {
                client.close();
            }
        }
    }

    /** Sends TCP RST only after consuming the complete bounded HTTP request. */
    private static final class ResettingPeer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> serving;
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> bodies = new CopyOnWriteArrayList<>();

        private ResettingPeer(int resets) throws Exception {
            listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            serving = executor.submit(() -> {
                try {
                    while (!listener.isClosed()) {
                        try (Socket socket = listener.accept()) {
                            socket.setSoTimeout(3000);
                            InputStream input = socket.getInputStream();
                            String first = line(input);
                            int length = 0;
                            boolean expectContinue = false;
                            for (String header; !(header = line(input)).isEmpty();) {
                                if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                                    length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                                }
                                if (header.equalsIgnoreCase("Expect: 100-continue")) {
                                    expectContinue = true;
                                }
                            }
                            if (length < 0 || length > 65536) {
                                throw new AssertionError("Fixture request body exceeds its bound");
                            }
                            if (expectContinue) {
                                socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                                socket.getOutputStream().flush();
                            }
                            byte[] body = input.readNBytes(length);
                            if (body.length != length) {
                                throw new AssertionError("Incomplete fixture request body");
                            }
                            methods.add(first.substring(0, first.indexOf(' ')));
                            bodies.add(new String(body, StandardCharsets.UTF_8));
                            if (methods.size() <= resets) {
                                socket.setSoLinger(true, 0);
                            } else {
                                socket.getOutputStream().write(("HTTP/1.1 204 No Content\r\n"
                                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                socket.getOutputStream().flush();
                            }
                        }
                    }
                } catch (SocketException stopped) {
                    if (!listener.isClosed()) {
                        throw new AssertionError(stopped);
                    }
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            });
        }

        private String url() {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/fixture";
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int next; (next = input.read()) != -1;) {
                if (next == '\n') {
                    return bytes.toString(StandardCharsets.US_ASCII).replace("\r", "");
                }
                bytes.write(next);
                if (bytes.size() > 8192) {
                    throw new IOException("Fixture HTTP line exceeds its bound");
                }
            }
            throw new IOException("Incomplete fixture HTTP line");
        }

        @Override
        public void close() throws Exception {
            listener.close();
            executor.shutdownNow();
            try {
                serving.get(5, TimeUnit.SECONDS);
            } finally {
                assertTrue("Fixture executor did not stop", executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
