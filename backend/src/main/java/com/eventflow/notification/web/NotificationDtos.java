package com.eventflow.notification.web;

import java.time.Instant;

public final class NotificationDtos {
  private NotificationDtos() {}

  public record NotificationView(
      long id, long orderId, String eventType, String message, Instant createdAt) {}
}
