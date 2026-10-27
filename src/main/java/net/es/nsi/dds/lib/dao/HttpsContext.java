package net.es.nsi.dds.lib.dao;

import com.google.common.base.Strings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import net.es.nsi.common.constants.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.glassfish.jersey.SslConfigurator;

/**
 * Process-wide mutable HTTPS security context.
 * Loading a configuration resolves store paths, populates omitted stores from
 * JSSE properties/defaults, and replaces the process default SSL context.
 * Initialization also registers Bouncy Castle providers when absent. Direct
 * callers must coordinate load-and-read sequences on this singleton; secure
 * {@code RestClient} construction does so when capturing its context. Provider
 * registration does not force BCJSSE selection: initialization requests the
 * default provider for the {@code TLS} algorithm.
 *
 * @author hacksaw
 */
public enum HttpsContext {
  INSTANCE;

  private final Logger LOG = LogManager.getLogger(getClass());
  private SSLContext sslContext;
  private boolean isProduction;

  /**
   * Registers the TLS security providers when absent.
   */
  private HttpsContext() {
    LOG.debug("[HttpsContext]: constructor invoked");

    // Preserve an existing provider registered under the BC name.
    if (Security.getProvider("BC") == null) {
      LOG.debug("Adding BouncyCastleProvider provider");
      Security.addProvider(new BouncyCastleProvider());
    }

    // Register BCJSSE without replacing the configured provider preference order.
    if (Security.getProvider("BCJSSE") == null) {
      LOG.debug("Adding BouncyCastleJsseProvider provider");
      Security.addProvider(new BouncyCastleJsseProvider());
    }

    LOG.debug("[HttpsContext]: constructor complete");
  }

  /**
   * Get the single instance of the HttpsContext object.
   *
   * @return The current HttpsContext object.
   */
  public static HttpsContext getInstance() {
    return INSTANCE;
  }

  /**
   * Loads configured stores and installs the resulting default SSL context.
   * Relative paths resolve against the common library's application base directory
   * property. This operation populates and normalizes the supplied configuration,
   * including before TLS initialization succeeds. A failed load leaves the prior
   * stored/default SSL context in place but need not leave config unchanged.
   *
   * @param config mutable TLS configuration; must not be null
   *
   * @throws KeyManagementException if the TLS context cannot be initialized
   * @throws NoSuchAlgorithmException if a required TLS or key algorithm is unavailable
   * @throws NoSuchProviderException retained by the TLS initialization API
   * @throws KeyStoreException if a store format is unavailable
   * @throws IOException if a store path cannot be resolved or read
   * @throws CertificateException if store certificate data cannot be loaded
   * @throws UnrecoverableKeyException if a configured key password cannot recover a key
   * @throws IllegalArgumentException if config is null
   */
  public synchronized void load(SecureType config) throws KeyManagementException, NoSuchAlgorithmException,
          NoSuchProviderException, KeyStoreException, IOException, CertificateException, UnrecoverableKeyException {

    LOG.debug("[HttpsContext].load invoked");

    if (config == null) {
      throw new IllegalArgumentException("[HttpsContext].load: server configuration not provided");
    }

    // We will use the application basedir to fully qualify any relative paths.
    String basedir = System.getProperty(Properties.SYSTEM_PROPERTY_BASEDIR);

    // Determine the keystore configuration.
    KeyStoreType keyStore = config.getKeyStore();
    if (keyStore == null) {
      // Check to see if the keystore was provided on the commandline.
      keyStore = new KeyStoreType();
      keyStore.setFile(System.getProperty(Properties.SYSTEM_PROPERTY_SSL_KEYSTORE, Properties.DEFAULT_SSL_KEYSTORE));
      keyStore.setPassword(System.getProperty(Properties.SYSTEM_PROPERTY_SSL_KEYSTORE_PASSWORD, Properties.DEFAULT_SSL_KEYSTORE_PASSWORD));
      keyStore.setType(System.getProperty(Properties.SYSTEM_PROPERTY_SSL_KEYSTORE_TYPE, Properties.DEFAULT_SSL_KEYSTORE_TYPE));
    }

    keyStore.setFile(getAbsolutePath(basedir, keyStore.getFile()));
    LOG.debug("[HttpsContext].load: keyStore " + keyStore.getFile());
    config.setKeyStore(keyStore);

    KeyStoreType trustStore = config.getTrustStore();
    if (trustStore == null) {
      // Check to see if the truststore was provided on the commandline.
      trustStore = new KeyStoreType();
      trustStore.setFile(System.getProperty(Properties.SYSTEM_PROPERTY_SSL_TRUSTSTORE, Properties.DEFAULT_SSL_TRUSTSTORE));
      trustStore.setPassword(System.getProperty(Properties.SYSTEM_PROPERTY_SSL_TRUSTSTORE_PASSWORD, Properties.DEFAULT_SSL_TRUSTSTORE_PASSWORD));
      trustStore.setType(System.getProperty(Properties.SYSTEM_PROPERTY_SSL_TRUSTSTORE_TYPE, Properties.DEFAULT_SSL_TRUSTSTORE_TYPE));
    }

    trustStore.setFile(getAbsolutePath(basedir, trustStore.getFile()));
    LOG.debug("[HttpsContext].load: trustStore " + trustStore.getFile());
    config.setTrustStore(trustStore);

    // Initialize the SSL context.
    sslContext = initContext(config);

    // Production boolean controls the hostverifier used during SSL setup.
    isProduction = config.isProduction();

    LOG.debug("[HttpsContext].load: done.");
  }

  /**
   * Resolves an existing store file, including symbolic links.
   *
   * @param basedir optional application directory for a relative input path
   * @param inPath configured store path
   * @return real absolute path
   * @throws IOException if the resolved path does not exist or is inaccessible
   */
  private String getAbsolutePath(String basedir, String inPath) throws IOException {
    Path outPath = Paths.get(inPath);
    if (!outPath.isAbsolute()) {
      if (!Strings.isNullOrEmpty(basedir)) {
        outPath = Paths.get(basedir, inPath);
      }
    }

    return outPath.toRealPath().toString();
  }

  /**
   * Get the default SSL context and add our specific configuration.
   *
   * @return New SSLContext for HTTP client.
   *
   * @throws java.security.KeyManagementException
   * @throws java.security.NoSuchAlgorithmException
   * @throws java.security.NoSuchProviderException
   * @throws java.security.KeyStoreException
   * @throws java.io.IOException
   * @throws java.security.cert.CertificateException
   * @throws java.security.UnrecoverableKeyException
   */
  private SSLContext initContext(SecureType st) throws KeyManagementException, NoSuchAlgorithmException,
          NoSuchProviderException, KeyStoreException, IOException, CertificateException, UnrecoverableKeyException {

    // Log what security providers are available to us.
    for (Provider provider : Security.getProviders()) {
      LOG.debug("[HttpsContext].initContext: Provider - {}, {}", provider.getName(), provider.getInfo());
    }

    try {
      // Configure the SSL context.
      SSLContext ctx = SSLContext.getInstance("TLS");

      // Initialize the KeyManagerFactory with provided configuration.
      KeyManagerFactory keyMgrFact = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyMgrFact.init(getKeyStore(st.getKeyStore()), st.getKeyStore().getPassword().toCharArray());

      // Initialize the TrustManagerFactory with provided configuration.
      TrustManagerFactory trustMgrFact = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustMgrFact.init(getKeyStore(st.getTrustStore()));

      // Initialize the SSL context with key managers.
      ctx.init(keyMgrFact.getKeyManagers(), trustMgrFact.getTrustManagers(), new SecureRandom());

      // Replace the process default context without changing provider ordering.
      SSLContext.setDefault(ctx);

      // Emit provider and protocol capabilities only at debug level.
      dumpSSLContext("[HttpsContext].initContext: defaultContext", SslConfigurator.getDefaultContext());
      return ctx;
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException
            | IOException | CertificateException | UnrecoverableKeyException ex) {
      LOG.error("[HttpsContext].initContext: could not find SSL provider", ex);
      throw ex;
    }
  }

  /**
   * Loads a configured store and closes the stream opened for it.
   *
   * @param ks store path, type, and password
   * @return loaded in-memory store
   * @throws KeyStoreException if the requested store format is unavailable
   * @throws IOException if the file cannot be read or its password is invalid
   * @throws NoSuchAlgorithmException if the store integrity algorithm is unavailable
   * @throws CertificateException if certificate data cannot be loaded
   */
  private KeyStore getKeyStore(KeyStoreType ks) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
    // Open specified keystore.
    File file = new File(ks.getFile());
    if (!file.exists()) {
      LOG.error("[HttpsContext].getKeyStore: file {} does not exist.", ks.getFile());
      throw new FileNotFoundException(String.format("[HttpsContext].getKeyStore: file {} does not exist", ks.getFile()));
    }
    KeyStore keyStore;
    try (InputStream stream = new FileInputStream(file)) {
      keyStore = KeyStore.getInstance(ks.getType());
      keyStore.load(stream, ks.getPassword().toCharArray());
    }
    return keyStore;
  }

  /**
   * Debug class that will dump information relating to the provided SSLContext.
   *
   * @param prefix - prefix pre-pended to the debug string.
   * @param c - SSLContext to dump to debug.
   */
  private void dumpSSLContext(String prefix, SSLContext c) {
    LOG.debug("{} - Provider = {}, {}", prefix, c.getProvider().getName(), c.getProvider().getInfo());

    SSLParameters supportedSSLParameters = c.getSupportedSSLParameters();
    String[] cipherSuites = supportedSSLParameters.getCipherSuites();
    for (String cipher : cipherSuites) {
      LOG.debug("{} - default cipher = {}", prefix, cipher);
    }

    for (String proto : supportedSSLParameters.getApplicationProtocols()) {
      LOG.debug("{} - application protocol = {}", prefix, proto);
    }
  }


  /**
   * Returns the last successfully loaded context without creating a new one.
   * Coordinate load-and-read operations on this singleton when used concurrently.
   *
   * @return shared context, or null before the first successful load
   */
  public SSLContext getSSLContext() {
    return sslContext;
  }

  /**
   * Returns the hostname-verification flag from the last successful load.
   * This getter does not itself enable hostname checks on an HTTP client.
   *
   * @return true if the loaded configuration selected production mode
   */
  public boolean isProduction() {
    return isProduction;
  }
}
