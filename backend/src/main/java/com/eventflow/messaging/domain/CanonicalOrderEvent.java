package com.eventflow.messaging.domain;

public record CanonicalOrderEvent(
    String eventId, OrderEventType eventType, OrderEventPayload payload, int attempts) {}
