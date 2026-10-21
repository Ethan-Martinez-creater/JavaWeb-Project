package com.eventflow.messaging.application;

import com.eventflow.messaging.application.ConfirmedMessagePublisher.ConfirmedPublishException;
import com.eventflow.messaging.config.MessagingNames;
import com.eventflow.messaging.config.MessagingProperties;
import com.eventflow.messaging.domain.CanonicalOrderEvent;
import com.eventflow.messaging.domain.OrderEventType;
import com.eventflow.messaging.domain.OutboxClaim;
import com.eventflow.messaging.domain.PoisonMessageException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claims and publishes small batches while keeping each network call outside a DB transaction. */
@Service
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxClaimService claims;
  private final CanonicalOrderEventReader eventReader;
  private final ConfirmedMessagePublisher publisher;
  private final MessagingProperties properties;
  private final Clock clock;

  public OutboxPublisher(
      OutboxClaimService claims,
      CanonicalOrderEventReader eventReader,
      ConfirmedMessagePublisher publisher,
      MessagingProperties properties,
      Clock clock) {
    this.claims = claims;
    this.eventReader = eventReader;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = clock;
  }

  /** Publishes up to the configured batch size, claiming each row immediately before its send. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int publishBatchOnce() {
    int published = 0;
    for (int index = 0; index < properties.getPublisherBatchSize(); index++) {
      OutboxClaim claim;
      try {
        claim = claims.claimOne();
      } catch (TransientDataAccessException error) {
        // A failed claim transaction is rolled back; an ambiguous connection loss recovers by
        // lease.
        log.warn("Outbox claim deferred type={}", error.getClass().getName());
        break;
      }
      if (claim == null) {
        break;
      }
      try {
        CanonicalOrderEvent event = eventReader.read(claim.eventId());
        publishEvent(event);
        if (claims.markSent(claim)) {
          published++;
        }
      } catch (PoisonMessageException exception) {
        claims.markFailure(claim, exception.reasonCode());
        log.error(
            "Outbox row needs repair eventId={} reason={}",
            claim.eventId(),
            exception.reasonCode());
      } catch (ConfirmedPublishException exception) {
        claims.markFailure(claim, exception.reasonCode());
        log.warn(
            "Outbox delivery deferred eventId={} reason={}",
            claim.eventId(),
            exception.reasonCode());
      } catch (RuntimeException exception) {
        claims.markFailure(claim, "PUBLISH_ERROR");
        log.error(
            "Outbox delivery deferred eventId={} type={}",
            claim.eventId(),
            exception.getClass().getName());
      }
    }
    return published;
  }

  private void publishEvent(CanonicalOrderEvent event) {
    if (event.eventType() == OrderEventType.ORDER_CREATED) {
      long remainingMs = Duration.between(clock.instant(), event.payload().expiresAt()).toMillis();
      if (remainingMs <= 0) {
        publisher.publish(MessagingNames.ORDER_EXPIRED_ROUTE, event.eventId(), Map.of(), null);
      } else {
        publisher.publish(
            MessagingNames.ORDER_CREATED_ROUTE, event.eventId(), Map.of(), remainingMs);
      }
      return;
    }
    String route =
        event.eventType() == OrderEventType.ORDER_PAID
            ? MessagingNames.ORDER_PAID_ROUTE
            : MessagingNames.ORDER_CLOSED_ROUTE;
    publisher.publish(route, event.eventId(), Map.of(), null);
  }
}
