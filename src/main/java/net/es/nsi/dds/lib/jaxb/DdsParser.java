package net.es.nsi.dds.lib.jaxb;

import java.io.IOException;
import java.io.InputStream;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import net.es.nsi.common.jaxb.JaxbParser;
import net.es.nsi.dds.lib.jaxb.dds.CollectionType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentListType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;
import net.es.nsi.dds.lib.jaxb.dds.NotificationListType;
import net.es.nsi.dds.lib.jaxb.dds.NotificationType;
import net.es.nsi.dds.lib.jaxb.dds.ObjectFactory;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionListType;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionRequestType;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionType;

/**
 * Singleton Jakarta JAXB facade for DDS, NSA, NML, and signature models.
 * The common parser reuses its JAXB context and creates operation-local
 * marshalling state. Stream methods leave caller-owned streams open; file
 * methods close the streams they open. The resolved common-library parser
 * rejects DTDs and external entities. Parsing does not imply schema validation.
 *
 * @author hacksaw
 */
public class DdsParser extends JaxbParser {

  private static final String PACKAGES = "net.es.nsi.dds.lib.jaxb.dds:net.es.nsi.common.jaxb.nsa" +
          ":net.es.nsi.common.jaxb.nml:net.es.nsi.common.jaxb.signatures";
  private static final ObjectFactory factory = new ObjectFactory();

  private DdsParser() {
    super(PACKAGES);
  }

  /**
   * An internal static class that invokes our private constructor on object creation.
   */
  private static class ParserHolder {

    public static final DdsParser INSTANCE = new DdsParser();
  }

  /**
   * Returns an instance of this singleton class.
   *
   * @return shared DDS parser
   */
  public static DdsParser getInstance() {
    return ParserHolder.INSTANCE;
  }

  /**
   * Reads a DDS document from a file and closes the owned input stream.
   *
   * @param filename input file path
   * @return parsed DDS document
   * @throws JAXBException if the XML cannot be bound
   * @throws IOException if the file cannot be read
   */
  public DocumentType readDocument(String filename) throws JAXBException, IOException {
    return this.parseFile(DocumentType.class, filename);
  }

  /**
   * Writes a DDS document to a file, replacing its contents and closing the stream.
   *
   * @param file output file path
   * @param document document to serialize with the DDS document root QName
   * @throws JAXBException if the document cannot be marshalled
   * @throws IOException if the file cannot be written
   */
  public void writeDocument(String file, DocumentType document) throws JAXBException, IOException {
    // Wrap the model with the DDS document root QName before marshalling.
    JAXBElement<DocumentType> element = factory.createDocument(document);
    this.writeFile(element, file);
  }

  /**
   * Reads a DDS collection from a file and closes the owned input stream.
   *
   * @param filename input file path
   * @return parsed collection
   * @throws JAXBException if the XML cannot be bound
   * @throws IOException if the file cannot be read
   */
  public CollectionType readCollection(String filename) throws JAXBException, IOException {
    return this.parseFile(CollectionType.class, filename);
  }

  /**
   * Parses a DDS document without closing the caller's stream, including on failure.
   *
   * @param is XML input owned by the caller
   * @return parsed DDS document
   * @throws JAXBException if the XML cannot be bound or contains a forbidden DTD
   * @throws IOException if input processing fails
   */
  public DocumentType xml2Document(InputStream is) throws JAXBException, IOException {
    return this.xml2Jaxb(DocumentType.class, is);
  }

  /**
   * Parses XML text as a DDS document.
   *
   * @param document XML text
   * @return parsed DDS document
   * @throws JAXBException if the XML cannot be bound or contains a forbidden DTD
   * @throws IOException if input processing fails
   */
  public DocumentType xml2Document(String document) throws JAXBException, IOException {
    return this.xml2Jaxb(DocumentType.class, document);
  }

  /**
   * Serializes the model using the DDS {@code document} root element.
   *
   * @param document model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String document2Xml(DocumentType document) throws JAXBException, IOException {
    JAXBElement<DocumentType> jaxb = factory.createDocument(document);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code documents} root element.
   *
   * @param documents model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String documents2Xml(DocumentListType documents) throws JAXBException, IOException {
    JAXBElement<DocumentListType> jaxb = factory.createDocuments(documents);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code collection} root element.
   *
   * @param collection model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String collection2Xml(CollectionType collection) throws JAXBException, IOException {
    JAXBElement<CollectionType> jaxb = factory.createCollection(collection);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code subscriptions} root element.
   *
   * @param list model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String subscriptions2Xml(SubscriptionListType list) throws JAXBException, IOException {
    JAXBElement<SubscriptionListType> jaxb = factory.createSubscriptions(list);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code subscription} root element.
   *
   * @param subscription model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String subscription2Xml(SubscriptionType subscription) throws JAXBException, IOException {
    JAXBElement<SubscriptionType> jaxb = factory.createSubscription(subscription);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code notifications} root element.
   *
   * @param list model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String notifications2Xml(NotificationListType list) throws JAXBException, IOException {
    JAXBElement<NotificationListType> jaxb = factory.createNotifications(list);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code notification} root element.
   *
   * @param notification model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String notification2Xml(NotificationType notification) throws JAXBException, IOException {
    JAXBElement<NotificationType> jaxb = factory.createNotification(notification);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes the model using the DDS {@code subscriptionRequest} root element.
   *
   * @param request model value to serialize
   * @return XML text preserving the DDS namespace and root QName
   * @throws JAXBException if the model cannot be marshalled
   * @throws IOException retained by the common parser's serialization API
   */
  public String subscriptionRequest2Xml(SubscriptionRequestType request) throws JAXBException, IOException {
    JAXBElement<SubscriptionRequestType> jaxb = factory.createSubscriptionRequest(request);
    return this.jaxb2Xml(jaxb);
  }

  /**
   * Serializes a DDS root with JAXB formatted output enabled.
   *
   * @param document model value to format
   * @return formatted XML text
   * @throws JAXBException if the model cannot be marshalled
   */
  public String xmlFormatter(DocumentType document) throws JAXBException {
    return this.jaxb2XmlFormatter(factory.createDocument(document));
  }

  /**
   * Serializes a DDS root with JAXB formatted output enabled.
   *
   * @param documents model value to format
   * @return formatted XML text
   * @throws JAXBException if the model cannot be marshalled
   */
  public String xmlFormatter(DocumentListType documents) throws JAXBException {
    return this.jaxb2XmlFormatter(factory.createDocuments(documents));
  }
}
