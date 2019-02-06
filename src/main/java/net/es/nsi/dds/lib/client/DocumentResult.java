package net.es.nsi.dds.lib.client;

import lombok.Data;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;

/**
 *
 * @author hacksaw
 */
@Data
public class DocumentResult extends Result {
  DocumentType document;
}