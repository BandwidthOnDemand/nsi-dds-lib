package net.es.nsi.dds.lib.test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

/**
 * Creates disposable localhost certificates with the running JDK's keytool.
 * The fixed password is test data; callers own the temporary output directory.
 */
public final class KeytoolFixture {
    public static final String PASSWORD = "test-password";
    public static final String ALIAS = "fixture";

    private KeytoolFixture() {
    }

    /** Creates a two-day PKCS12 localhost identity, with bounded keytool execution. */
    public static File createKeyStore(File directory, String name) throws Exception {
        Path keyStore = directory.toPath().resolve(name + ".p12");
        Path log = directory.toPath().resolve(name + "-keytool.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("keytool did not terminate");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("keytool failed: " + Files.readString(log));
        }
        return keyStore.toFile();
    }

    /** Writes a JKS truststore containing only the fixture identity certificate. */
    public static File createTrustStore(File directory, String name, File keyStoreFile) throws Exception {
        KeyStore source = load(keyStoreFile, "PKCS12");
        KeyStore trust = KeyStore.getInstance("JKS");
        trust.load(null, PASSWORD.toCharArray());
        trust.setCertificateEntry(ALIAS, source.getCertificate(ALIAS));
        File output = new File(directory, name + ".jks");
        try (var stream = Files.newOutputStream(output.toPath())) {
            trust.store(stream, PASSWORD.toCharArray());
        }
        return output;
    }

    /** Loads a fixture store with the test password and closes its input stream. */
    public static KeyStore load(File file, String type) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        try (var stream = Files.newInputStream(file.toPath())) {
            store.load(stream, PASSWORD.toCharArray());
        }
        return store;
    }
}
