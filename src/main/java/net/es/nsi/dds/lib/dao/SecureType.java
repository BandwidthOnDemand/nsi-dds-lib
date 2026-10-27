package net.es.nsi.dds.lib.dao;

/**
 * Mutable TLS store configuration and hostname-verification mode.
 * {@code production=true} enables hostname verification; the default false
 * permits hostname mismatches. Both modes retain certificate trust validation.
 * Omitted stores are populated from JSSE properties/defaults by {@link HttpsContext},
 * which mutates this configuration to contain resolved store paths. Do not share a
 * configuration across concurrent mutation or log its generated string representation,
 * which includes nested store passwords.
 *
 * @author hacksaw
 */
@lombok.Builder
@lombok.Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class SecureType {
  private KeyStoreType keyStore;
  private KeyStoreType trustStore;
  private boolean production;
}
