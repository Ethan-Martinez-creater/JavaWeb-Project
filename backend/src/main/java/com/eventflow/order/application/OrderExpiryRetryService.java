package com.eventflow.order.application;

import com.eventflow.order.infrastructure.OrderMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists backoff after the failed close transaction has rolled back through its proxy. */
@Service
public class OrderExpiryRetryService {
  private final OrderMapper mapper;
  private final Clock clock;
  private final long baseMillis;
  private final long maxMillis;

  public OrderExpiryRetryService(
      OrderMapper mapper,
      Clock clock,
      @Value("${app.orders.expiry-retry-base-ms:1000}") long baseMillis,
      @Value("${app.orders.expiry-retry-max-ms:300000}") long maxMillis) {
    if (baseMillis < 1 || maxMillis < baseMillis || maxMillis > 86400000) {
      throw new IllegalArgumentException("Invalid expiry retry configuration");
    }
    this.mapper = mapper;
    this.clock = clock;
    this.baseMillis = baseMillis;
    this.maxMillis = maxMillis;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void defer(long id) {
    Integer attempts = mapper.expiryRetryAttempts(id);
    if (attempts == null) {
      return;
    }
    long multiplier = 1L << Math.min(attempts, 20);
    long delay = Math.min(maxMillis, baseMillis * multiplier);
    mapper.deferExpiry(
        id, LocalDateTime.ofInstant(clock.instant().plusMillis(delay), ZoneOffset.UTC));
  }
}
