package com.eventflow.messaging.domain;

import java.time.LocalDateTime;

/** Mutable MyBatis representation of a persisted outbox event. */
public class OutboxEventEntity {
  private String eventId;
  private Long aggregateId;
  private String eventType;
  private String payload;
  private Integer attempts;
  private LocalDateTime createdAt;
  private LocalDateTime nextAttemptAt;

  public String getEventId() {
    return eventId;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public Long getAggregateId() {
    return aggregateId;
  }

  public void setAggregateId(Long aggregateId) {
    this.aggregateId = aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }

  public Integer getAttempts() {
    return attempts;
  }

  public void setAttempts(Integer attempts) {
    this.attempts = attempts;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
    this.nextAttemptAt = nextAttemptAt;
  }
}
