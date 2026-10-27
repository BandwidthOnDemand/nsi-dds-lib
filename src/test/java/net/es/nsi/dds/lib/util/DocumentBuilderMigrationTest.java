package net.es.nsi.dds.lib.util;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.datatype.DatatypeFactory;
import net.es.nsi.common.jaxb.DomParser;
import net.es.nsi.common.signing.KeyStoreHandler;
import net.es.nsi.common.signing.SignatureFactory;
import net.es.nsi.common.util.Encoder;
import net.es.nsi.dds.lib.jaxb.DdsParser;
import net.es.nsi.dds.lib.jaxb.dds.ContentType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;
import net.es.nsi.dds.lib.test.KeytoolFixture;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import static org.junit.Assert.*;

/** Checks envelope metadata, encoding, malformed gzip/XML, and optional signature verification. */
public class DocumentBuilderMigrationTest {
    @ClassRule
    public static TemporaryFolder temporary = new TemporaryFolder();
    private static File keyStore;

    @BeforeClass
    public static void keys() throws Exception {
        keyStore = KeytoolFixture.createKeyStore(temporary.getRoot(), "signing");
    }

    @Test
    public void unsignedUnicodeDocumentPreservesMetadataAndEncoding() throws Exception {
        DocumentType built = builder(payload("東京 – réseau")).build();
        assertEquals("urn:nsa:test", built.getNsa());
        assertEquals("topology", built.getType());
        assertEquals("doc-東京", built.getId());
        assertEquals("2026-10-26T12:34:56.789Z", built.getVersion().toXMLFormat());
        assertEquals("application/x-gzip", built.getContent().getContentType());
        assertEquals("base64", built.getContent().getContentTransferEncoding());
        assertNull(built.getSignature());
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(built.getContent().getValue())))) {
            assertTrue(new String(gzip.readAllBytes(), StandardCharsets.UTF_8).contains("東京 – réseau"));
        }
        String xml = DdsParser.getInstance().document2Xml(built);
        Document decoded = DocumentBuilder.verify(DdsParser.getInstance().xml2Document(xml));
        assertEquals("urn:test:payload", decoded.getDocumentElement().getNamespaceURI());
        assertEquals("東京 – réseau", decoded.getDocumentElement().getTextContent());
    }

    @Test
    public void emptySignatureRetainsUnsignedAcceptance() throws Exception {
        DocumentType built = builder(payload("unsigned")).build();
        ContentType signature = new ContentType();
        signature.setValue("");
        built.setSignature(signature);
        assertEquals("unsigned", DocumentBuilder.verify(built).getDocumentElement().getTextContent());
    }

    @Test
    public void verifiesDetachedSignatureAndRejectsContentTampering() throws Exception {
        Document contents = payload("signed 東京");
        SignatureFactory factory = new SignatureFactory(new KeyStoreHandler(
                keyStore.toString(), KeytoolFixture.PASSWORD, "PKCS12"));
        Document signature = factory.generateExternalSignature(contents, KeytoolFixture.ALIAS);
        DocumentType built = builder(contents).withSignature(signature).build();
        DocumentType roundtrip = DdsParser.getInstance().xml2Document(DdsParser.getInstance().document2Xml(built));
        assertEquals("signed 東京", DocumentBuilder.verify(roundtrip).getDocumentElement().getTextContent());
        roundtrip.getContent().setValue(Encoder.encode(payload("tampered")));
        assertThrows(SecurityException.class, () -> DocumentBuilder.verify(roundtrip));
    }

    @Test
    public void rejectsMissingContentsAndInvalidCompressedPayload() throws Exception {
        DocumentType empty = new DocumentType();
        assertThrows(IllegalArgumentException.class, () -> DocumentBuilder.verify(empty));
        DocumentType built = builder(payload("data")).build();
        built.getContent().setValue(Base64.getEncoder().encodeToString("not gzip".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> DocumentBuilder.verify(built));
    }

    @Test
    public void rejectsEveryTruncatedGzipTrailer() throws Exception {
        DocumentType built = builder(payload("complete XML before the trailer")).build();
        byte[] gzip = Base64.getDecoder().decode(built.getContent().getValue());
        for (int missing = 1; missing <= 8; missing++) {
            built.getContent().setValue(Base64.getEncoder().encodeToString(
                    Arrays.copyOf(gzip, gzip.length - missing)));
            assertThrows("Missing " + missing + " gzip trailer bytes", IOException.class,
                    () -> DocumentBuilder.verify(built));
        }
    }

    @Test
    public void rejectsCorruptGzipCrcAndSize() throws Exception {
        DocumentType built = builder(payload("gzip integrity")).build();
        byte[] gzip = Base64.getDecoder().decode(built.getContent().getValue());
        for (int offset : new int[] { 8, 4 }) {
            byte[] corrupt = gzip.clone();
            corrupt[corrupt.length - offset] ^= 1;
            built.getContent().setValue(Base64.getEncoder().encodeToString(corrupt));
            assertThrows("Corrupted gzip trailer field at offset " + offset, IOException.class,
                    () -> DocumentBuilder.verify(built));
        }
    }

    @Test
    public void rejectsTruncatedGzipHeaderAndCompressedBody() throws Exception {
        DocumentType built = builder(payload("truncated compressed XML")).build();
        byte[] gzip = Base64.getDecoder().decode(built.getContent().getValue());
        for (int length : new int[] { 1, 9, 10 + (gzip.length - 18) / 2 }) {
            built.getContent().setValue(Base64.getEncoder().encodeToString(Arrays.copyOf(gzip, length)));
            assertThrows("Truncated gzip length " + length, IOException.class,
                    () -> DocumentBuilder.verify(built));
        }
    }

    @Test
    public void rejectsTruncatedGzipSignatureBeforeValidation() throws Exception {
        Document contents = payload("signed with complete content");
        SignatureFactory factory = new SignatureFactory(new KeyStoreHandler(
                keyStore.toString(), KeytoolFixture.PASSWORD, "PKCS12"));
        Document signature = factory.generateExternalSignature(contents, KeytoolFixture.ALIAS);
        DocumentType built = builder(contents).withSignature(signature).build();
        assertEquals("signed with complete content",
                DocumentBuilder.verify(built).getDocumentElement().getTextContent());
        byte[] gzip = Base64.getDecoder().decode(built.getSignature().getValue());
        for (int missing = 1; missing <= 8; missing++) {
            built.getSignature().setValue(Base64.getEncoder().encodeToString(
                    Arrays.copyOf(gzip, gzip.length - missing)));
            assertThrows("Missing " + missing + " signature gzip trailer bytes", IOException.class,
                    () -> DocumentBuilder.verify(built));
        }
    }

    @Test
    public void rejectsMalformedXmlInCompleteGzip() throws Exception {
        DocumentType built = builder(payload("contents")).build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write("<payload>unclosed".getBytes(StandardCharsets.UTF_8));
        }
        built.getContent().setValue(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        IOException failure = assertThrows(IOException.class, () -> DocumentBuilder.verify(built));
        assertTrue(failure.getCause() instanceof org.xml.sax.SAXException);
    }

    @Test
    public void preservesUncompressedXmlWithExplicitAndDefaultEncodings() throws Exception {
        DocumentType built = builder(payload("contents")).build();
        String xml = "<payload xmlns=\"urn:test:payload\">東京 – réseau</payload>";
        for (String encoding : new String[] { "binary", null, "" }) {
            built.getContent().setContentType(encoding == null ? null : "text/xml");
            built.getContent().setContentTransferEncoding(encoding);
            built.getContent().setValue(xml);
            Document decoded = DocumentBuilder.verify(built);
            assertEquals("urn:test:payload", decoded.getDocumentElement().getNamespaceURI());
            assertEquals("東京 – réseau", decoded.getDocumentElement().getTextContent());
        }
    }

    @Test
    public void rejectsTamperedDetachedSignatureValue() throws Exception {
        Document contents = payload("unchanged signed contents");
        SignatureFactory factory = new SignatureFactory(new KeyStoreHandler(
                keyStore.toString(), KeytoolFixture.PASSWORD, "PKCS12"));
        Document signature = factory.generateExternalSignature(contents, KeytoolFixture.ALIAS);
        DocumentType built = builder(contents).withSignature(signature).build();
        var value = signature.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "SignatureValue").item(0);
        String encoded = value.getTextContent().replaceAll("\\s", "");
        value.setTextContent((encoded.charAt(0) == 'A' ? "B" : "A") + encoded.substring(1));
        built.getSignature().setValue(Encoder.encode(signature));
        assertThrows(SecurityException.class, () -> DocumentBuilder.verify(built));
    }

    @Test
    public void detachedSignatureProtectsPayloadRatherThanEnvelopeMetadata() throws Exception {
        Document contents = payload("signed payload");
        SignatureFactory factory = new SignatureFactory(new KeyStoreHandler(
                keyStore.toString(), KeytoolFixture.PASSWORD, "PKCS12"));
        Document signature = factory.generateExternalSignature(contents, KeytoolFixture.ALIAS);
        DocumentType built = builder(contents).withSignature(signature).build();
        built.setNsa("urn:nsa:changed-envelope");
        built.setId("changed-envelope-id");
        built.setType("changed-envelope-type");
        assertEquals("signed payload", DocumentBuilder.verify(built).getDocumentElement().getTextContent());
    }

    @Test
    public void nonemptyMalformedSignatureIsRejectedRatherThanTreatedAsUnsigned() throws Exception {
        DocumentType built = builder(payload("contents")).build();
        ContentType malformed = new ContentType();
        malformed.setContentType(DocumentBuilder.ContentType);
        malformed.setContentTransferEncoding(DocumentBuilder.ContentTransferEncoding);
        malformed.setValue(Encoder.encode(payload("not a signature")));
        built.setSignature(malformed);
        assertThrows(SecurityException.class, () -> DocumentBuilder.verify(built));
    }

    private static DocumentBuilder builder(Document contents) throws Exception {
        return new DocumentBuilder().withNsaId("urn:nsa:test").withType("topology").withId("doc-東京")
                .withVersion(DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-26T12:34:56.789Z"))
                .withExpires(DatatypeFactory.newInstance().newXMLGregorianCalendar("2027-01-01T00:00:00Z"))
                .withContents(contents);
    }

    private static Document payload(String text) throws Exception {
        Document document = DomParser.xml2Dom("<payload xmlns=\"urn:test:payload\"/>");
        document.getDocumentElement().setTextContent(text);
        return document;
    }
}
