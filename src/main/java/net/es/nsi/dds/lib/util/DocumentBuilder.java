package net.es.nsi.dds.lib.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.parsers.ParserConfigurationException;
import net.es.nsi.common.jaxb.DomParser;
import net.es.nsi.common.signing.Validate;
import net.es.nsi.common.util.Decoder;
import net.es.nsi.common.util.Encoder;
import net.es.nsi.dds.lib.dao.IllegalArgumentExceptionSupplier;
import net.es.nsi.dds.lib.jaxb.dds.ContentType;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;
import net.es.nsi.dds.lib.jaxb.dds.ObjectFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Builds DDS envelopes with gzip-compressed XML and Base64 transfer encoding.
 * Instances are mutable and retain references to supplied DOM documents and
 * calendar values. Complete all required metadata and contents before building.
 *
 * <p>Signatures are optional. Building logs signature-validation failures but
 * still includes the supplied signature; use {@link #verify(DocumentType)} to
 * reject invalid nonempty signatures. Neither operation establishes certificate
 * trust or signer authorization.
 *
 * @author hacksaw
 */
public class DocumentBuilder {

  /** Media type assigned to gzip-compressed content and signatures. */
  public final static String ContentType = "application/x-gzip";
  /** Transfer encoding applied after gzip compression. */
  public final static String ContentTransferEncoding = "base64";

  private final static Logger LOG = LogManager.getLogger(DocumentBuilder.class);
  private final ObjectFactory factory = new ObjectFactory();

  protected Optional<String> nsaId = Optional.empty();
  protected Optional<String> type = Optional.empty();
  protected Optional<String> id = Optional.empty();
  protected Optional<String> href = Optional.empty();
  protected Optional<XMLGregorianCalendar> version = Optional.empty();
  protected Optional<XMLGregorianCalendar> expires = Optional.empty();
  protected Optional<Document> doc = Optional.empty();
  protected Optional<Document> sig = Optional.empty();

  /**
   * Sets the required NSA identifier.
   *
   * @param nsaId non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withNsaId(String nsaId) {
    this.nsaId = Optional.of(nsaId);
    return this;
  }

  /**
   * Sets the required document type.
   *
   * @param type non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withType(String type) {
    this.type = Optional.of(type);
    return this;
  }

  /**
   * Sets the required document identifier.
   *
   * @param id non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withId(String id) {
    this.id = Optional.of(id);
    return this;
  }

  /**
   * Retains the required version timestamp by reference.
   *
   * @param version non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withVersion(XMLGregorianCalendar version) {
    this.version = Optional.of(version);
    return this;
  }

  /**
   * Retains the required expiry timestamp by reference.
   *
   * @param expires non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withExpires(XMLGregorianCalendar expires) {
    this.expires = Optional.of(expires);
    return this;
  }

  /**
   * Retains the required content DOM by reference; it is encoded during build.
   *
   * @param doc non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withContents(Document doc) {
    this.doc = Optional.of(doc);
    return this;
  }

  /**
   * Retains an optional detached signature DOM by reference for encoding during build.
   *
   * @param sig non-null value; no defensive copy is made
   * @return this mutable builder
   * @throws NullPointerException if the value is null
   */
  public DocumentBuilder withSignature(Document sig) {
    this.sig = Optional.of(sig);
    return this;
  }

  /**
   * Creates an envelope from the configured metadata and DOM payload.
   * A supplied signature is encoded even if its preliminary validation fails.
   *
   * @return new DDS document envelope
   * @throws IllegalArgumentException if required metadata or contents are absent
   * @throws IOException if payload or signature encoding fails
   */
  public DocumentType build() throws IllegalArgumentException, IOException {
    // Create the document we want to add to DDS.
    DocumentType document = factory.createDocumentType();

    // Populate document meta-data based on NSA document.
    document.setNsa(nsaId.orElseThrow(new IllegalArgumentExceptionSupplier("document nsaId required")));
    document.setType(type.orElseThrow(new IllegalArgumentExceptionSupplier("document type required")));
    document.setId(id.orElseThrow(new IllegalArgumentExceptionSupplier("document id required")));
    document.setVersion(version.orElseThrow(new IllegalArgumentExceptionSupplier("document version required")));
    document.setExpires(expires.orElseThrow(new IllegalArgumentExceptionSupplier("document expires time required")));

    // Encode the DOM document and stick it in contents.
    String docEncoded = Encoder.encode(doc.orElseThrow(new IllegalArgumentExceptionSupplier("document contents required")));
    ContentType contentHolder = factory.createContentType();
    contentHolder.setValue(docEncoded);
    contentHolder.setContentType(ContentType);
    contentHolder.setContentTransferEncoding(ContentTransferEncoding);
    document.setContent(contentHolder);

    if (sig.isPresent()) {
      try {
        if (!Validate.validateExternal(doc.get(), sig.get())) {
          LOG.error("Failed to validate signature.");
        }
      } catch (Exception ex) {
        LOG.error("Failed to validate signature.", ex);
      }

      String sigEncoded = Encoder.encode(sig.get());
      ContentType sigHolder = factory.createContentType();
      sigHolder.setValue(sigEncoded);
      sigHolder.setContentType(ContentType);
      sigHolder.setContentTransferEncoding(ContentTransferEncoding);
      document.setSignature(sigHolder);
    }

    return document;
  }

  /**
   * Decodes envelope contents and checks any nonempty detached signature.
   * Transfer encoding is decoded before content decompression. Decoded streams
   * are consumed to completion, including gzip integrity checks. Missing or empty
   * signatures are accepted; validation does not establish signer trust.
   *
   * @param document non-null envelope with non-null, nonempty content text
   * @return decoded content DOM
   * @throws IllegalArgumentException if contents are absent or empty
   * @throws IOException if payload or signature decoding fails, including
   * malformed XML or invalid gzip content
   * @throws SecurityException if a nonempty signature cannot be validated
   * @throws NullPointerException if the envelope or a present content/signature
   *         value is null
   */
  public static Document verify(DocumentType document)
          throws IllegalArgumentException, IOException {
    LOG.debug("Verifying document nsaId={}, type={}, id={}", document.getNsa(),
            document.getType(), document.getId());

    // Get the document contents.
    ContentType contents = document.getContent();
    if (contents == null || contents.getValue().isEmpty()) {
      throw new IllegalArgumentException("verify: No document contents present");
    }

    // Decode the document contents.
    Document contentsDecoded = decodeContent(contents);

    // Get signature if present.
    ContentType signature = document.getSignature();
    if (signature != null && !signature.getValue().isEmpty()) {
      // Validate signature.
      Document signatureDecoded = decodeContent(signature);

      boolean valid = false;
      try {
        valid = Validate.validateExternal(contentsDecoded, signatureDecoded);
      } catch (Exception ex) {
        LOG.error("validateExternal: failed to validate document", ex);
      }

      if (!valid) {
        throw new SecurityException("verify: signature for document invalid");
      }
    }

    return contentsDecoded;
  }

    private static Document decodeContent(ContentType content) throws IOException {
        try (InputStream decoded = Decoder.decode(content.getContentTransferEncoding(),
                content.getContentType(), content.getValue())) {
            Document document = DomParser.xml2Dom(decoded);
            // XML parsing can accept EOFException after the root element. Read
            // outside the parser so a missing gzip trailer cannot be accepted.
            decoded.transferTo(OutputStream.nullOutputStream());
            return document;
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }
}
