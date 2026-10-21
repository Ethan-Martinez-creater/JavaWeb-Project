package com.eventflow.messaging.application;

import com.eventflow.messaging.config.MessagingNames;
import com.eventflow.messaging.config.MessagingProperties;
import com.eventflow.messaging.domain.PoisonMessageException;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** Shared decoding, retry, and quarantine rules for the two manual-ack consumers. */
@Component
public class ReliableMessageDelivery {
  private final ConfirmedMessagePublisher publisher;
  private final MessagingProperties properties;

  public ReliableMessageDelivery(
      ConfirmedMessagePublisher publisher, MessagingProperties properties) {
    this.publisher = publisher;
    this.properties = properties;
  }

  public String eventId(Message message) {
    String value = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    try {
      CanonicalOrderEventReader.requireCanonicalUuid(value);
      return value;
    } catch (PoisonMessageException exception) {
      throw exception;
    }
  }

  public int retryCount(Message message) {
    Object raw = message.getMessageProperties().getHeaders().get(MessagingNames.RETRY_COUNT_HEADER);
    if (raw == null) {
      return 0;
    }
    try {
      String text = raw.toString();
      if (!text.matches("[0-9]{1,10}")) {
        throw new NumberFormatException();
      }
      long value = Long.parseLong(text);
      if (value < 0 || value > Integer.MAX_VALUE) {
        throw new NumberFormatException();
      }
      return (int) value;
    } catch (NumberFormatException | ClassCastException exception) {
      throw new PoisonMessageException("INVALID_RETRY_COUNT");
    }
  }

  public void quarantine(String eventIdOrReason, String reasonCode) {
    quarantine(eventIdOrReason, reasonCode, 0);
  }

  private void quarantine(String eventIdOrReason, String reasonCode, int retries) {
    publisher.publish(
        MessagingNames.DEAD_LETTER_ROUTE,
        eventIdOrReason,
        Map.of(
            MessagingNames.ERROR_CODE_HEADER,
            reasonCode,
            MessagingNames.RETRY_COUNT_HEADER,
            retries),
        null);
  }

  public void retryOrQuarantine(
      Message message, String eventId, String retryRoute, String reasonCode) {
    int retryCount = retryCount(message);
    if (retryCount >= properties.getConsumerMaxRetries()) {
      quarantine(eventId, reasonCode, retryCount);
      return;
    }
    String delayRoute =
        retryRoute.equals(MessagingNames.ORDER_EXPIRED_ROUTE)
            ? MessagingNames.ORDER_EXPIRY_RETRY_ROUTE
            : MessagingNames.NOTIFICATION_RETRY_DELAY_ROUTE;
    publisher.publish(
        delayRoute,
        eventId,
        Map.of(
            MessagingNames.RETRY_COUNT_HEADER,
            retryCount + 1,
            MessagingNames.ERROR_CODE_HEADER,
            reasonCode),
        null);
  }

  public void postponeExpiry(String eventId, long remainingMs) {
    publisher.publish(
        MessagingNames.ORDER_CREATED_ROUTE, eventId, Map.of(), Math.max(1, remainingMs));
  }
}
