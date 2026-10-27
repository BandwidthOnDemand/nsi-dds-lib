package net.es.nsi.dds.lib.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.glassfish.jersey.apache5.connector.Apache5ClientProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks retry-policy decisions directly, including request bodies, cancellation, and excluded I/O failures. */
public class RestClientRetryTest {
    private RestClient client;
    private HttpRequestRetryStrategy strategy;

    @Before
    public void create() throws Exception {
        client = new RestClient();
        strategy = (HttpRequestRetryStrategy) client.get().getConfiguration()
                .getProperty(Apache5ClientProperties.RETRY_STRATEGY);
    }

    @After
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void entitylessIdempotentRequestsHaveThreeImmediateIoRetries() {
        BasicClassicHttpRequest request = new BasicClassicHttpRequest("GET", "/");
        for (int count = 1; count <= 3; count++) {
            assertTrue(strategy.retryRequest(request, new IOException("reset"), count, new HttpCoreContext()));
        }
        assertFalse(strategy.retryRequest(request, new IOException("reset"), 4, new HttpCoreContext()));
        assertEquals(0, strategy.getRetryInterval(new BasicHttpResponse(503), 1, new HttpCoreContext())
                .toMilliseconds());
    }

    @Test
    public void repeatableBodiesAndNonIdempotentRequestsAreNotReplayed() {
        for (String method : new String[]{"GET", "PUT", "DELETE"}) {
            BasicClassicHttpRequest request = new BasicClassicHttpRequest(method, "/");
            request.setEntity(new StringEntity("repeatable payload"));
            assertFalse(method, strategy.retryRequest(request, new IOException("reset"), 1, new HttpCoreContext()));
        }
        BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        assertFalse(strategy.retryRequest(post, new IOException("reset"), 1, new HttpCoreContext()));
    }

    @Test
    public void remoteStatusNeverTriggersRetryEvenWithRetryAfter() {
        for (int status : new int[]{429, 503}) {
            BasicHttpResponse response = new BasicHttpResponse(status);
            response.setHeader("Retry-After", "1");
            assertFalse(strategy.retryRequest(response, 1, new HttpCoreContext()));
        }
    }

    @Test
    public void interruptedFailedConnectionsAndTlsFailuresAreNotRetried() {
        for (IOException failure : new IOException[]{new InterruptedIOException(), new SocketTimeoutException(),
                new UnknownHostException(), new ConnectException(), new SSLException("handshake")}) {
            assertFalse(failure.getClass().getName(), strategy.retryRequest(
                    new BasicClassicHttpRequest("GET", "/"), failure, 1, new HttpCoreContext()));
        }
    }

    @Test
    public void cancelledRequestIsNotRetried() {
        HttpUriRequestBase request = new HttpUriRequestBase("GET", java.net.URI.create("http://localhost/"));
        request.cancel();
        assertFalse(strategy.retryRequest(request, new IOException("reset"), 1, new HttpCoreContext()));
    }
}
