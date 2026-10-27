package net.es.nsi.dds.lib.client;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.logging.Level;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import net.es.nsi.dds.lib.dao.HttpsContext;
import net.es.nsi.dds.lib.dao.SecureType;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpEntityContainer;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.apache5.connector.Apache5ClientProperties;
import org.glassfish.jersey.apache5.connector.Apache5ConnectorProvider;
import org.glassfish.jersey.apache5.connector.Apache5HttpClientBuilderConfigurator;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.glassfish.jersey.client.filter.EncodingFilter;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.message.GZipEncoder;
import org.glassfish.jersey.moxy.xml.MoxyXmlFeature;

/**
 * Owns a pooled Jakarta REST client backed by Apache HttpClient 5.
 * Call {@link #close()} when the client is no longer needed. Callers issuing
 * requests through {@link #get()} own and must close their responses.
 *
 * <p>Defaults are 10 connections per route, 80 total, and connect/socket/pool
 * acquisition timeouts of 20000/60000/30000 milliseconds. The corresponding
 * system properties are {@code tcpConnectTimeout}, {@code tcpSoTimeout}, and
 * {@code tcpConnectRequestTimeout}. Jersey handles gzip encoding and decoding.
 * Eligible entity-less idempotent requests have at most three immediate I/O
 * retries; HTTP response statuses are never retried. HTTPS downgrades are refused.
 *
 * <p>A configured {@link SecureType} enforces certificate trust in both modes;
 * production mode additionally verifies hostnames. Constructing a secure client
 * loads the process-wide {@link HttpsContext} and updates the default SSL context.
 * Without an explicit secure configuration, the connector uses its default TLS
 * configuration; this does not disable HTTPS certificate or hostname checks.
 * Pool-setting setters change the fields used by subsequent configuration calls;
 * they do not reconfigure the client already created by the constructor.
 *
 * @author hacksaw
 */
@lombok.Data
public class RestClient {
  private static final Logger LOG = LogManager.getLogger(RestClient.class);

  // Time for idle data timeout.
  private static final String TCP_SO_TIMEOUT = "tcpSoTimeout";
  private static final int SO_TIMEOUT = 60 * 1000;

  // Time for the socket to connect.
  private static final String TCP_CONNECT_TIMEOUT = "tcpConnectTimeout";
  private static final int CONNECT_TIMEOUT = 20 * 1000;

  // Time to block for a socket from the connection manager.
  private static final String TCP_CONNECT_REQUEST_TIMEOUT = "tcpConnectRequestTimeout";
  private static final int CONNECT_REQUEST_TIMEOUT = 30 * 1000;

  // Connection provider pool configuration defaults.
  private int maxConnPerRoute = 10;

  private int maxConnTotal = 80;

  // Security context.
  private SecureType secure;

  private final Client client;

  /**
   * Creates an owned client with default pool limits and connector TLS settings.
   * The checked exceptions are retained for constructor/API compatibility.
   *
   * @throws java.security.KeyManagementException
   * @throws java.security.NoSuchAlgorithmException
   * @throws java.security.NoSuchProviderException
   * @throws java.security.KeyStoreException
   * @throws java.io.IOException
   * @throws java.security.cert.CertificateException
   * @throws java.security.UnrecoverableKeyException
   */
  public RestClient() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          KeyManagementException, UnrecoverableKeyException, NoSuchProviderException {
    client = getRestClient();
  }

  /**
   * Creates a pooled client with explicit connection limits.
   *
   * @param maxConnPerRoute maximum connections per destination route
   * @param maxConnTotal maximum total pooled connections
   * @throws KeyStoreException if a configured store type cannot be loaded
   * @throws IOException if a configured store cannot be read
   * @throws NoSuchAlgorithmException if a required TLS or key algorithm is unavailable
   * @throws CertificateException if a store contains invalid certificates
   * @throws KeyManagementException if TLS context initialization fails
   * @throws UnrecoverableKeyException if a private key cannot be recovered
   * @throws NoSuchProviderException retained by the TLS configuration API
   */
  public RestClient(int maxConnPerRoute, int maxConnTotal) throws KeyStoreException, IOException,
          NoSuchAlgorithmException, CertificateException, KeyManagementException, UnrecoverableKeyException,
          NoSuchProviderException {

    this.maxConnPerRoute = maxConnPerRoute;
    this.maxConnTotal = maxConnTotal;

    client = getRestClient();
  }

  /**
   * Creates a pooled client with explicit connection limits.
   *
   * @param maxConnPerRoute maximum connections per destination route
   * @param maxConnTotal maximum total pooled connections
   * @param secure mutable store configuration and hostname-verification mode;
   *               null selects the connector defaults
   * @throws KeyStoreException if a configured store type cannot be loaded
   * @throws IOException if a configured store cannot be read
   * @throws NoSuchAlgorithmException if a required TLS or key algorithm is unavailable
   * @throws CertificateException if a store contains invalid certificates
   * @throws KeyManagementException if TLS context initialization fails
   * @throws UnrecoverableKeyException if a private key cannot be recovered
   * @throws NoSuchProviderException retained by the TLS configuration API
   */
  public RestClient(int maxConnPerRoute, int maxConnTotal, SecureType secure) throws KeyStoreException, IOException,
          NoSuchAlgorithmException, CertificateException, KeyManagementException, UnrecoverableKeyException,
          NoSuchProviderException {

    this.maxConnPerRoute = maxConnPerRoute;
    this.maxConnTotal = maxConnTotal;
    this.secure = secure;

    client = getRestClient();
  }

  private Client getRestClient() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          KeyManagementException, UnrecoverableKeyException, NoSuchProviderException {
    ClientConfig clientConfig;

    if (secure != null) {
      LOG.debug("[RestClient] initializing secure client");
      HostnameVerifier hostnameVerifier;
      if (secure.isProduction()) {
        hostnameVerifier = new DefaultHostnameVerifier();
      } else {
        hostnameVerifier = NoopHostnameVerifier.INSTANCE;
      }

      SSLContext sslContext;
      synchronized (HttpsContext.getInstance()) {
        HttpsContext.getInstance().load(secure);
        sslContext = HttpsContext.getInstance().getSSLContext();
      }

      PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
              .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                      .setSslContext(sslContext)
                      .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
                      .setHostnameVerifier(hostnameVerifier)
                      .buildClassic())
              .build();
      clientConfig = getClientConfig(connectionManager);
    }
    else {
      LOG.debug("[RestClient] initializing insecure client");
      PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
      clientConfig = getClientConfig(connectionManager);
    }

    Client c = ClientBuilder.newBuilder().withConfig(clientConfig).build();
    c.property(LoggingFeature.LOGGING_FEATURE_LOGGER_LEVEL_CLIENT, Level.FINEST.getName());
    c.property(LoggingFeature.LOGGING_FEATURE_VERBOSITY_CLIENT, LoggingFeature.Verbosity.PAYLOAD_ANY);
    return c;
  }

  /**
   * Configures the supplied HTTP5 pool and a Jersey client that owns it.
   *
   * @param connectionManager pool to configure with this instance's limits and timeouts
   * @return configuration for a client that closes the pool when closed
   * @throws NumberFormatException if a timeout property is not a decimal integer
   * @throws IllegalArgumentException if pool limits or timeout values are invalid
   */
  public ClientConfig getClientConfig(PoolingHttpClientConnectionManager connectionManager) {
    ClientConfig clientConfig = new ClientConfig();

    // Apache5 sends subscription bodies using Jersey's chunked entity processing.
    clientConfig.connectorProvider(new Apache5ConnectorProvider());
    connectionManager.setDefaultMaxPerRoute(maxConnPerRoute);
    connectionManager.setMaxTotal(maxConnTotal);
    // This is an immediate sweep, not a recurring idle-connection eviction task.
    connectionManager.closeIdle(TimeValue.ofSeconds(30));
    connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(Integer.parseInt(System.getProperty(TCP_CONNECT_TIMEOUT, Integer.toString(CONNECT_TIMEOUT)))))
            .setSocketTimeout(Timeout.ofMilliseconds(Integer.parseInt(System.getProperty(TCP_SO_TIMEOUT, Integer.toString(SO_TIMEOUT)))))
            .build());
    clientConfig.property(Apache5ClientProperties.CONNECTION_MANAGER, connectionManager);

    // Let Jersey perform content decoding exactly once instead of HttpClient.
    clientConfig.register((Apache5HttpClientBuilderConfigurator) builder ->
            builder.disableContentCompression().setRedirectStrategy(new CompatibleRedirectStrategy()));
    clientConfig.property(Apache5ClientProperties.RETRY_STRATEGY, new CompatibleRetryStrategy());
    clientConfig.register(GZipEncoder.class);
    clientConfig.register(EncodingFilter.class);
    clientConfig.register(new MoxyXmlFeature());
    // Payload logging can expose DDS content when the application enables it.
    clientConfig.register(new LoggingFeature(java.util.logging.Logger.getGlobal(), Level.ALL,
            LoggingFeature.Verbosity.PAYLOAD_ANY, 10000));
    clientConfig.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.CHUNKED);

    // Apache specific configuration.
    RequestConfig.Builder custom = RequestConfig.custom();
    custom.setExpectContinueEnabled(true);
    custom.setRedirectsEnabled(true);
    custom.setResponseTimeout(Timeout.ofMilliseconds(Integer.parseInt(System.getProperty(TCP_SO_TIMEOUT, Integer.toString(SO_TIMEOUT)))));
    custom.setConnectionRequestTimeout(Timeout.ofMilliseconds(Integer.parseInt(System.getProperty(TCP_CONNECT_REQUEST_TIMEOUT, Integer.toString(CONNECT_REQUEST_TIMEOUT)))));
    clientConfig.property(Apache5ClientProperties.REQUEST_CONFIG, custom.build());

    return clientConfig;
  }

  /**
   * Returns the shared client; callers must close responses from direct requests.
   *
   * @return client owned by this wrapper
   */
  public Client get() {
    return client;
  }

  /** Closes the shared client and its connection pool. */
  public void close() {
    client.close();
  }

  // Preserve the three immediate I/O retries without HTTP5's new status retries.
  // Only entity-less idempotent requests may be replayed; streaming and buffered
  // request bodies are excluded even for otherwise idempotent HTTP methods.
  private static class CompatibleRetryStrategy extends DefaultHttpRequestRetryStrategy {
    CompatibleRetryStrategy() {
      super(3, TimeValue.ZERO_MILLISECONDS);
    }

    @Override
    protected boolean handleAsIdempotent(HttpRequest request) {
      return Method.isIdempotent(request.getMethod())
              && (!(request instanceof HttpEntityContainer)
              || ((HttpEntityContainer) request).getEntity() == null);
    }

    @Override
    public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
      return false;
    }
  }

  // Match HTTP4's redirectable methods/statuses instead of replaying POST/PUT
  // under HTTP5's broader default policy. HTTP5 resolves relative locations,
  // bounds redirect chains, closes intermediate responses, and guards credentials.
  private static class CompatibleRedirectStrategy extends DefaultRedirectStrategy {
    @Override
    public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
            throws ProtocolException {
      // HTTP5 passes the initial request to this callback on every hop. The
      // context tracks the request that produced this response (possibly GET
      // after a 303), which determines whether the next redirect is allowed.
      HttpRequest currentRequest = context == null ? null : HttpCoreContext.cast(context).getRequest();
      if (currentRequest == null) {
        currentRequest = request;
      }
      int status = response.getCode();
      boolean safeMethod = "GET".equalsIgnoreCase(currentRequest.getMethod())
              || "HEAD".equalsIgnoreCase(currentRequest.getMethod());
      return (status == 303 || (safeMethod && (status == 301 || status == 302 || status == 307 || status == 308)))
              && super.isRedirected(currentRequest, response, context);
    }

    @Override
    public boolean isRedirectAllowed(HttpHost currentTarget, HttpHost newTarget,
            HttpRequest redirect, HttpContext context) {
      if ("https".equalsIgnoreCase(currentTarget.getSchemeName())
              && "http".equalsIgnoreCase(newTarget.getSchemeName())) {
        return false;
      }
      return super.isRedirectAllowed(currentTarget, newTarget, redirect, context);
    }
  }
}
