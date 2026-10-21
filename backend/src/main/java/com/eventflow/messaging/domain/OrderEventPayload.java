package com.eventflow.messaging.domain;

import java.time.Instant;

/** Canonical outbox payload; queue messages carry only its event ID. */
public record OrderEventPayload(String eventId, long orderId, long userId, Instant expiresAt) {}
