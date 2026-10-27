package net.es.nsi.dds.lib.dao;

/**
 * Mutable key/truststore file, password, and format configuration.
 * The format defaults to {@code JKS}. Paths must identify readable store files.
 * Treat this object and its generated accessors/string representation as sensitive:
 * its password is retained as a string and is not redacted by Lombok.
 *
 * @author hacksaw
 */
@lombok.Builder
@lombok.Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class KeyStoreType {
  private String file;
  private String password;
  @lombok.Builder.Default
  private String type = "JKS";
}
