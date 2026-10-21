package com.eventflow.messaging.domain;

/** A leased outbox event returned after its claim transaction commits. */
public record OutboxClaim(
    String eventId,
    long aggregateId,
    String eventType,
    String payload,
    int attempts,
    String leaseOwner) {}
