package com.eventflow.messaging.domain;

/** A permanent message error that should be sent directly to the dead-letter queue. */
public class PoisonMessageException extends RuntimeException {
  private final String reasonCode;

  public PoisonMessageException(String reasonCode) {
    super(reasonCode);
    this.reasonCode = reasonCode;
  }

  public String reasonCode() {
    return reasonCode;
  }
}
