package com.eventflow.messaging.application;

import java.time.Instant;

public record ExpiryHandlingResult(Outcome outcome, Instant expiresAt) {
  public enum Outcome {
    COMPLETED,
    ALREADY_COMPLETED,
    NOT_DUE
  }
}
