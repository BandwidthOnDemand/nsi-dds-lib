package net.es.nsi.dds.lib.client;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.es.nsi.dds.lib.jaxb.dds.DocumentType;

/**
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode(callSuper=true)
public class DocumentsResult extends Result {
  List<DocumentType> documents = new ArrayList<>();
}