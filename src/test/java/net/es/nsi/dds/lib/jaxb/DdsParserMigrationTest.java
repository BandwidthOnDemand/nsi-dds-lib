package net.es.nsi.dds.lib.jaxb;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import net.es.nsi.common.jaxb.DomParser;
import net.es.nsi.common.jaxb.nml.NmlTopologyType;
import net.es.nsi.common.jaxb.nsa.NsaType;
import net.es.nsi.common.jaxb.signatures.SignatureType;
import net.es.nsi.dds.lib.jaxb.dds.CollectionType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Checks DDS root compatibility, Unicode, parser restrictions, and caller-owned stream preservation. */
public class DdsParserMigrationTest {
    private static final String NS = "http://schemas.ogf.org/nsi/2014/02/discovery/types";
    private static final String DATE = "2026-10-26T12:34:56.789+05:30";
    private static final String DOCUMENT = root("document", " id=\"doc-東京\" version=\"" + DATE
            + "\" expires=\"2027-01-01T00:00:00Z\"", "<nsa>urn:nsa:test</nsa><type>réseau</type>");
    private final DdsParser parser = DdsParser.getInstance();

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preservesEveryDdsRootAndSchemaShape() throws Exception {
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("collection", root("collection", "", ""));
        fixtures.put("documents", root("documents", "", DOCUMENT));
        fixtures.put("local", root("local", "", DOCUMENT));
        fixtures.put("document", DOCUMENT);
        String subscription = root("subscription", " id=\"s\" href=\"/subscriptions/s\" version=\"" + DATE + "\"",
                "<requesterId>urn:nsa:test</requesterId><callback>https://localhost/callback</callback>");
        fixtures.put("subscription", subscription);
        fixtures.put("subscriptions", root("subscriptions", "", subscription));
        fixtures.put("subscriptionRequest", root("subscriptionRequest", "",
                "<requesterId>urn:nsa:test</requesterId><callback>https://localhost/callback</callback>"));
        String notification = root("notification", "", "<discovered>" + DATE + "</discovered><event>New</event>"
                + DOCUMENT.replace("dds:document", "document"));
        fixtures.put("notification", notification);
        fixtures.put("notifications", root("notifications", " providerId=\"urn:nsa:test\" id=\"n\" href=\"/notifications/n\"", notification));
        fixtures.put("error", root("error", " id=\"e\" date=\"" + DATE + "\"",
                "<code>400</code><label>Invalid</label><description>échec</description><resource>urn:test</resource>"));

        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var schema = schemaFactory.newSchema(new File("src/main/schema/dds/xsd/ogf_nsi_discovery_protocol_v1_0.xsd"));
        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            schema.newValidator().validate(new StreamSource(new StringReader(fixture.getValue())));
            JAXBElement<?> element = parser.dom2Jaxb(DomParser.xml2Dom(fixture.getValue()));
            assertEquals(fixture.getKey(), element.getName().getLocalPart());
            assertEquals(NS, element.getName().getNamespaceURI());
            String serialized = parser.jaxb2Xml(element);
            schema.newValidator().validate(new StreamSource(new StringReader(serialized)));
            assertEquals(element.getName(), parser.dom2Jaxb(DomParser.xml2Dom(serialized)).getName());
        }
        DocumentType document = parser.xml2Document(DOCUMENT);
        assertEquals("doc-東京", document.getId());
        assertEquals("réseau", document.getType());
        assertEquals(DATE, document.getVersion().toXMLFormat());
    }

    @Test
    public void contextIncludesCommonNsaNmlAndSignatureModels() throws Exception {
        CollectionType collection = new CollectionType();
        collection.getAny().add(new net.es.nsi.common.jaxb.nsa.ObjectFactory().createNsa(new NsaType()));
        collection.getAny().add(new net.es.nsi.common.jaxb.nml.ObjectFactory().createTopology(new NmlTopologyType()));
        collection.getAny().add(new net.es.nsi.common.jaxb.signatures.ObjectFactory().createSignature(new SignatureType()));
        CollectionType result = parser.xml2Jaxb(CollectionType.class, parser.collection2Xml(collection));
        assertEquals(3, result.getAny().size());
        assertTrue(((JAXBElement<?>) result.getAny().get(0)).getValue() instanceof NsaType);
        assertTrue(((JAXBElement<?>) result.getAny().get(1)).getValue() instanceof NmlTopologyType);
        assertTrue(((JAXBElement<?>) result.getAny().get(2)).getValue() instanceof SignatureType);
    }

    @Test
    public void leavesCallerStreamsOpenOnSuccessAndMalformedXml() throws Exception {
        TrackingStream valid = new TrackingStream(DOCUMENT);
        assertEquals("doc-東京", parser.xml2Document(valid).getId());
        assertFalse(valid.closed);
        TrackingStream invalid = new TrackingStream("<broken");
        assertThrows(JAXBException.class, () -> parser.xml2Document(invalid));
        assertFalse(invalid.closed);
    }

    @Test
    public void rejectsDtdAndExternalEntitiesWithoutClosingCallerStream() throws Exception {
        File secret = temporary.newFile("secret.txt");
        Files.writeString(secret.toPath(), "must-not-be-expanded", StandardCharsets.UTF_8);
        String xml = "<!DOCTYPE document [<!ENTITY external SYSTEM '" + secret.toURI() + "'>]>"
                + DOCUMENT.replace("réseau", "&external;");
        TrackingStream stream = new TrackingStream(xml);
        assertThrows(JAXBException.class, () -> parser.xml2Document(stream));
        assertFalse(stream.closed);
        assertThrows(JAXBException.class, () -> parser.xml2Document(xml));
    }

    @Test
    public void fileFacadePreservesUnicodeAndCanOverwriteItsOwnOutput() throws Exception {
        File output = temporary.newFile("document.xml");
        DocumentType input = parser.xml2Document(DOCUMENT);
        parser.writeDocument(output.toString(), input);
        assertEquals("doc-東京", parser.readDocument(output.toString()).getId());
        input.setType("updated");
        parser.writeDocument(output.toString(), input);
        assertEquals("updated", parser.readDocument(output.toString()).getType());
    }

    private static String root(String local, String attributes, String content) {
        return "<dds:" + local + " xmlns:dds=\"" + NS + "\"" + attributes + ">"
                + content + "</dds:" + local + ">";
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingStream(String xml) {
            super(xml.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
