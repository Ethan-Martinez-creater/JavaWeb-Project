package com.eventflow.notification.application;

import com.eventflow.messaging.application.CanonicalOrderEventReader;
import com.eventflow.messaging.application.ConsumerDeduplicationService;
import com.eventflow.messaging.domain.CanonicalOrderEvent;
import com.eventflow.messaging.domain.OrderEventType;
import com.eventflow.messaging.domain.PoisonMessageException;
import com.eventflow.notification.domain.NotificationEntity;
import com.eventflow.notification.infrastructure.NotificationMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates one owner-scoped notification per paid or closed order event. */
@Service
public class NotificationMessageHandler {
  private static final String CONSUMER_NAME = "notification-v1";

  private final CanonicalOrderEventReader eventReader;
  private final ConsumerDeduplicationService deduplication;
  private final NotificationMapper notifications;
  private final Clock clock;

  public NotificationMessageHandler(
      CanonicalOrderEventReader eventReader,
      ConsumerDeduplicationService deduplication,
      NotificationMapper notifications,
      Clock clock) {
    this.eventReader = eventReader;
    this.deduplication = deduplication;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Transactional
  public void handle(String eventId) {
    CanonicalOrderEvent event = eventReader.read(eventId);
    if (event.eventType() != OrderEventType.ORDER_PAID
        && event.eventType() != OrderEventType.ORDER_CLOSED) {
      throw new PoisonMessageException("WRONG_NOTIFICATION_EVENT_TYPE");
    }
    LocalDateTime now =
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    if (!deduplication.tryClaim(eventId, CONSUMER_NAME, now)) {
      return;
    }

    String type = event.eventType().name();
    String message =
        event.eventType() == OrderEventType.ORDER_PAID
            ? "Order #" + event.payload().orderId() + " was paid."
            : "Order #" + event.payload().orderId() + " was closed.";
    NotificationEntity notification = new NotificationEntity();
    notification.setEventId(eventId);
    notification.setUserId(event.payload().userId());
    notification.setOrderId(event.payload().orderId());
    notification.setEventType(type);
    notification.setMessage(message);
    notification.setCreatedAt(now);
    if (notifications.insert(notification) != 1) {
      throw new IllegalStateException("Notification insert failed");
    }
  }
}
