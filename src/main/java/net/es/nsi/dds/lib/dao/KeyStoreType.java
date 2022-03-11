package net.es.nsi.dds.lib.dao;

/**
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
