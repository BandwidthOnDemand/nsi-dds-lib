package net.es.nsi.dds.lib.client;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.es.nsi.dds.lib.jaxb.dds.SubscriptionType;

/**
 * Mutable operation result with a nullable subscription list.
 * Lists and their elements are retained by reference. Equality and hashing use
 * only the list, excluding inherited status and last-modified metadata.
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class SubscriptionsResult extends Result {
  List<SubscriptionType> subscriptions;
}
