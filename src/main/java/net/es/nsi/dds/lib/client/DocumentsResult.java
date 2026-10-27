package net.es.nsi.dds.lib.client;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;

/**
 * Mutable operation result whose document list initially is empty.
 * The list and its elements are exposed directly. Unlike the other payload
 * wrappers, equality and hashing include inherited status and last-modified metadata.
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode(callSuper=true)
public class DocumentsResult extends Result {
  List<DocumentType> documents = new ArrayList<>();
}
