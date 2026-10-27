package net.es.nsi.dds.lib.client;

import jakarta.ws.rs.core.Response.Status;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Mutable HTTP operation result using Jakarta REST status values.
 * The default status is {@code BAD_REQUEST}; unknown HTTP codes can map to null.
 * {@code lastModified} is epoch milliseconds, with zero meaning unset. Equality
 * and hashing include both fields, which are mutable; do not mutate an instance
 * while it is used as a hash-based collection key.
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode
public class Result {
  Status status = Status.BAD_REQUEST;
  long lastModified;
}
