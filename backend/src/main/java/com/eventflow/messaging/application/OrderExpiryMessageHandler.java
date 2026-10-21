package com.eventflow.messaging.application;

import com.eventflow.messaging.domain.CanonicalOrderEvent;
import com.eventflow.messaging.domain.OrderEventType;
import com.eventflow.messaging.domain.PoisonMessageException;
import com.eventflow.order.application.OrderService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies an expired-order event and its dedupe marker in the same transaction. */
@Service
public class OrderExpiryMessageHandler {
  private static final String CONSUMER_NAME = "order-expiry-v1";

  private final CanonicalOrderEventReader eventReader;
  private final ConsumerDeduplicationService deduplication;
  private final OrderService orders;
  private final Clock clock;

  public OrderExpiryMessageHandler(
      CanonicalOrderEventReader eventReader,
      ConsumerDeduplicationService deduplication,
      OrderService orders,
      Clock clock) {
    this.eventReader = eventReader;
    this.deduplication = deduplication;
    this.orders = orders;
    this.clock = clock;
  }

  @Transactional
  public ExpiryHandlingResult handle(String eventId) {
    CanonicalOrderEvent event = eventReader.read(eventId);
    if (event.eventType() != OrderEventType.ORDER_CREATED) {
      throw new PoisonMessageException("WRONG_EXPIRY_EVENT_TYPE");
    }
    if (event.payload().expiresAt().isAfter(clock.instant())) {
      return new ExpiryHandlingResult(
          ExpiryHandlingResult.Outcome.NOT_DUE, event.payload().expiresAt());
    }

    LocalDateTime now =
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    if (!deduplication.tryClaim(eventId, CONSUMER_NAME, now)) {
      return new ExpiryHandlingResult(
          ExpiryHandlingResult.Outcome.ALREADY_COMPLETED, event.payload().expiresAt());
    }
    // closeExpired joins this transaction, so the marker cannot commit ahead of the state change.
    orders.closeExpired(event.payload().orderId());
    return new ExpiryHandlingResult(
        ExpiryHandlingResult.Outcome.COMPLETED, event.payload().expiresAt());
  }
}
