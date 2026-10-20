package com.eventflow.common.error;

import java.time.Instant;
import org.slf4j.MDC;

public record ApiError(String code, String message, String requestId, Instant timestamp) {
  public static ApiError of(String code, String message) {
    return new ApiError(code, message, MDC.get("requestId"), Instant.now());
  }
}
