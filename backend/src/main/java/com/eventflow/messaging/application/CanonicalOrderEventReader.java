package com.eventflow.messaging.application;

import com.eventflow.messaging.domain.CanonicalOrderEvent;
import com.eventflow.messaging.domain.OrderEventPayload;
import com.eventflow.messaging.domain.OrderEventType;
import com.eventflow.messaging.domain.OutboxEventEntity;
import com.eventflow.messaging.domain.PoisonMessageException;
import com.eventflow.messaging.infrastructure.OutboxMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Resolves messages against the durable outbox instead of trusting broker payloads. */
@Component
public class CanonicalOrderEventReader {
  private final OutboxMapper mapper;
  private final ObjectMapper objectMapper;

  public CanonicalOrderEventReader(OutboxMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  public CanonicalOrderEvent read(String eventId) {
    requireCanonicalUuid(eventId);
    OutboxEventEntity row = mapper.findByEventId(eventId);
    if (row == null) {
      throw new PoisonMessageException("UNKNOWN_EVENT_ID");
    }
    OrderEventType eventType = OrderEventType.fromDatabase(row.getEventType());
    OrderEventPayload payload;
    try {
      payload = objectMapper.readValue(row.getPayload(), OrderEventPayload.class);
    } catch (JacksonException exception) {
      throw new PoisonMessageException("INVALID_EVENT_PAYLOAD");
    }
    if (payload == null
        || !eventId.equals(payload.eventId())
        || payload.orderId() <= 0
        || payload.userId() <= 0
        || payload.expiresAt() == null
        || row.getAggregateId() == null
        || payload.orderId() != row.getAggregateId()
        || row.getAttempts() == null
        || row.getAttempts() < 0) {
      throw new PoisonMessageException("INVALID_EVENT_PAYLOAD");
    }
    return new CanonicalOrderEvent(eventId, eventType, payload, row.getAttempts());
  }

  public static void requireCanonicalUuid(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equals(value)) {
        throw new PoisonMessageException("INVALID_EVENT_ID");
      }
    } catch (IllegalArgumentException exception) {
      throw new PoisonMessageException("INVALID_EVENT_ID");
    }
  }
}
