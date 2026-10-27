package net.es.nsi.dds.lib.util;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import javax.xml.datatype.DatatypeFactory;
import net.es.nsi.common.jaxb.DomParser;
import net.es.nsi.dds.lib.jaxb.DdsParser;
import net.es.nsi.dds.lib.jaxb.dds.ContentType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Runs compressible and corrupt gzip inputs in a heap-limited child JVM.
 * These bounded fixtures catch allocation regressions; they do not establish a
 * general maximum size for untrusted document expansion.
 */
public class DocumentAllocationTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 25000)
    public void compressiblePayloadRoundTripsWithinBoundedChildHeap() throws Exception {
        runIsolated("roundtrip");
    }

    @Test(timeout = 25000)
    public void forgedHugeGzipSizeFailsWithinBoundedChildHeap() throws Exception {
        runIsolated("forged-size");
    }

    @Test(timeout = 25000)
    public void truncatedGzipTrailerFailsWithinBoundedChildHeap() throws Exception {
        runIsolated("truncated");
    }

    private void runIsolated(String scenario) throws Exception {
        Path log = temporary.newFile(scenario + ".log").toPath();
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx96m", "-XX:MaxDirectMemorySize=16m", "-XX:+ExitOnOutOfMemoryError",
                "-XX:ErrorFile=" + temporary.getRoot().toPath().resolve("jvm-error-%p.log"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PayloadProbe.class.getName(), scenario)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("Allocation probe did not terminate", process.waitFor(15, TimeUnit.SECONDS));
            assertTrue("Unexpectedly large probe output", Files.size(log) < 65536);
            String output = Files.readString(log);
            assertEquals(output, 0, process.exitValue());
            assertTrue(output, output.contains("PAYLOAD_OK"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue("Allocation probe could not be stopped", process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    public static final class PayloadProbe {
        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                throw new AssertionError("A probe scenario is required");
            }
            if ("roundtrip".equals(args[0])) {
                String text = "東京-" + "x".repeat(2 * 1024 * 1024) + "-réseau";
                var contents = DomParser.xml2Dom("<payload xmlns=\"urn:test:payload\"/>");
                contents.getDocumentElement().setTextContent(text);
                var calendars = DatatypeFactory.newInstance();
                DocumentType built = new DocumentBuilder().withNsaId("urn:nsa:allocation")
                        .withType("topology").withId("bounded-payload")
                        .withVersion(calendars.newXMLGregorianCalendar("2026-10-26T00:00:00Z"))
                        .withExpires(calendars.newXMLGregorianCalendar("2027-01-01T00:00:00Z"))
                        .withContents(contents).build();
                if (built.getContent().getValue().length() >= 65536) {
                    throw new AssertionError("Fixture did not exercise a compressible payload");
                }
                DocumentType roundtrip = DdsParser.getInstance().xml2Document(
                        DdsParser.getInstance().document2Xml(built));
                if (!text.equals(DocumentBuilder.verify(roundtrip).getDocumentElement().getTextContent())) {
                    throw new AssertionError("Expanded payload changed during document round trip");
                }
            } else if ("forged-size".equals(args[0]) || "truncated".equals(args[0])) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                    gzip.write("<payload xmlns=\"urn:test:payload\">small</payload>".getBytes(StandardCharsets.UTF_8));
                }
                byte[] correct = bytes.toByteArray();
                if ("forged-size".equals(args[0])) {
                    byte[] forgedSize = correct.clone();
                    Arrays.fill(forgedSize, forgedSize.length - 4, forgedSize.length, (byte) 0xff);
                    requireDecodingFailure(forgedSize);
                } else {
                    byte[] truncated = Arrays.copyOf(correct, correct.length - 5);
                    // Establish that the fixture is incomplete compressed data even
                    // though its XML document element can already be decompressed.
                    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(truncated))) {
                        try {
                            gzip.readAllBytes();
                            throw new AssertionError("Truncation fixture is a complete gzip stream");
                        } catch (IOException expected) {
                            // Full gzip consumption must validate the missing trailer.
                        }
                    }
                    requireDecodingFailure(truncated);
                }
            } else {
                throw new AssertionError("Unknown probe scenario");
            }
            System.out.println("PAYLOAD_OK");
        }

        private static void requireDecodingFailure(byte[] gzip) throws Exception {
            ContentType content = new ContentType();
            content.setContentType(DocumentBuilder.ContentType);
            content.setContentTransferEncoding(DocumentBuilder.ContentTransferEncoding);
            content.setValue(Base64.getEncoder().encodeToString(gzip));
            DocumentType document = new DocumentType();
            document.setContent(content);
            try {
                DocumentBuilder.verify(document);
                throw new AssertionError("Malformed gzip was accepted");
            } catch (IOException expected) {
                // A forged trailer must not drive an allocation based on its claimed size.
            }
        }
    }
}
