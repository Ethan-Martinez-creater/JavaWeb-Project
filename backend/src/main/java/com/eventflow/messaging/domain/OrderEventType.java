package com.eventflow.messaging.domain;

/** Order state transitions that are durably recorded in the outbox. */
public enum OrderEventType {
  ORDER_CREATED,
  ORDER_PAID,
  ORDER_CLOSED;

  public static OrderEventType fromDatabase(String value) {
    try {
      return OrderEventType.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new PoisonMessageException("INVALID_EVENT_TYPE");
    }
  }
}
