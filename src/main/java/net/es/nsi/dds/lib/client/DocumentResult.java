package net.es.nsi.dds.lib.client;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;

/**
 * Mutable operation result with a nullable DDS document payload.
 * Equality and hashing use only the document; inherited status and last-modified
 * metadata are deliberately excluded for compatibility.
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class DocumentResult extends Result {
  DocumentType document;
}
