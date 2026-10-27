package net.es.nsi.dds.lib.client;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionType;

/**
 * Mutable operation result with a nullable subscription payload.
 * A successful deletion or an unchanged conditional read has no payload. Equality
 * and hashing use only the subscription, excluding inherited result metadata.
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class SubscriptionResult extends Result {
  SubscriptionType subscription;
}
