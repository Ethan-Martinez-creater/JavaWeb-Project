package com.eventflow.messaging.application;

import com.eventflow.messaging.domain.OrderEventPayload;
import com.eventflow.messaging.domain.OrderEventType;
import com.eventflow.messaging.domain.OutboxEventEntity;
import com.eventflow.messaging.infrastructure.OutboxMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Records an order transition in the caller's transaction. */
@Service
public class OutboxRecorder {
  private final OutboxMapper mapper;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public OutboxRecorder(OutboxMapper mapper, ObjectMapper objectMapper, Clock clock) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String record(String eventType, long orderId, long userId, Instant expiresAt) {
    OrderEventType type = OrderEventType.fromDatabase(eventType);
    if (orderId <= 0 || userId <= 0 || expiresAt == null) {
      throw new IllegalArgumentException("Invalid order event data");
    }
    String eventId = UUID.randomUUID().toString();
    OrderEventPayload payload = new OrderEventPayload(eventId, orderId, userId, expiresAt);
    String json;
    try {
      json = objectMapper.writeValueAsString(payload);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to serialize order event", exception);
    }

    LocalDateTime recordedAt = utc(clock.instant());
    OutboxEventEntity event = new OutboxEventEntity();
    event.setEventId(eventId);
    event.setAggregateId(orderId);
    event.setEventType(type.name());
    event.setPayload(json);
    event.setCreatedAt(recordedAt);
    event.setNextAttemptAt(recordedAt);
    if (mapper.insert(event) != 1) {
      throw new IllegalStateException("Outbox event insert failed");
    }
    return eventId;
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
