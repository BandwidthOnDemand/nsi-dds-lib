package net.es.nsi.dds.lib.dao;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Creates a fresh missing-argument exception for lazy validation, such as
 * {@link java.util.Optional#orElseThrow(java.util.function.Supplier)}.
 *
 * @author hacksaw
 */
public class IllegalArgumentExceptionSupplier implements Supplier<IllegalArgumentException> {

  private Optional<String> error = Optional.empty();

  /**
   * Uses a custom message, or the default message when null.
   *
   * @param error optional diagnostic message
   */
  public IllegalArgumentExceptionSupplier(String error) {
    this.error = Optional.ofNullable(error);
  }

  /** Uses the default required-member diagnostic. */
  public IllegalArgumentExceptionSupplier() {
  }

  /**
   * Creates an exception on demand without caching an earlier stack trace.
   *
   * @return new exception with the configured or default message
   */
  @Override
  public IllegalArgumentException get() {
    return new IllegalArgumentException(error.orElse("Required member data missing"));

  }
}
