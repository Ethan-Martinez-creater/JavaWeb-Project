package com.eventflow.messaging.application;

import com.eventflow.messaging.config.MessagingProperties;
import com.eventflow.messaging.domain.OutboxClaim;
import com.eventflow.messaging.domain.OutboxEventEntity;
import com.eventflow.messaging.infrastructure.OutboxMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each database lease operation in its own short transaction. */
@Service
public class OutboxClaimService {
  private final OutboxMapper mapper;
  private final Clock clock;
  private final MessagingProperties properties;

  public OutboxClaimService(OutboxMapper mapper, Clock clock, MessagingProperties properties) {
    this.mapper = mapper;
    this.clock = clock;
    this.properties = properties;
  }

  @Transactional
  public OutboxClaim claimOne() {
    LocalDateTime now = utc(clock);
    OutboxEventEntity event = mapper.lockNextDue(now);
    if (event == null) {
      return null;
    }
    String owner = UUID.randomUUID().toString();
    LocalDateTime leaseUntil = now.plus(properties.getClaimLeaseMs(), ChronoUnit.MILLIS);
    if (mapper.claim(event.getEventId(), owner, now, leaseUntil) != 1) {
      return null;
    }
    int nextAttempts =
        event.getAttempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : event.getAttempts() + 1;
    return new OutboxClaim(
        event.getEventId(),
        event.getAggregateId(),
        event.getEventType(),
        event.getPayload(),
        nextAttempts,
        owner);
  }

  @Transactional
  public boolean markSent(OutboxClaim claim) {
    return mapper.markSent(claim.eventId(), claim.leaseOwner(), utc(clock)) == 1;
  }

  @Transactional
  public boolean markFailure(OutboxClaim claim, String errorCode) {
    LocalDateTime now = utc(clock);
    return mapper.markFailure(
            claim.eventId(),
            claim.leaseOwner(),
            now.plus(backoffMs(claim.attempts()), ChronoUnit.MILLIS),
            normalizeCode(errorCode))
        == 1;
  }

  private long backoffMs(int attempts) {
    long delay = properties.getPublishBackoffBaseMs();
    long maximum = properties.getPublishBackoffMaxMs();
    for (int index = 1; index < attempts && delay < maximum; index++) {
      delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
    }
    return delay;
  }

  private static String normalizeCode(String code) {
    if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
      return "PUBLISH_ERROR";
    }
    return code;
  }

  private static LocalDateTime utc(Clock clock) {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
