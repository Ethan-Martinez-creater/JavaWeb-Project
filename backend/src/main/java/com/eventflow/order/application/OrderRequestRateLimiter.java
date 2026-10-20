package com.eventflow.order.application;

import com.eventflow.common.error.ApiException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-user fixed-window limiter; a Redis outage is fail-open because MySQL remains authoritative.
 */
@Component
public class OrderRequestRateLimiter {
  private static final Logger log = LoggerFactory.getLogger(OrderRequestRateLimiter.class);
  private static final String KEY_PREFIX = "eventflow:rate:orders:user:";
  private static final DefaultRedisScript<Long> INCREMENT_WITH_FIRST_HIT_EXPIRY =
      new DefaultRedisScript<>(
          "local count = redis.call('INCR', KEYS[1]); "
              + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
              + "return count",
          Long.class);

  private final StringRedisTemplate redis;
  private final boolean enabled;
  private final long limit;
  private final long windowMs;
  private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

  public OrderRequestRateLimiter(
      StringRedisTemplate redis,
      @Value("${app.rate-limit.orders.enabled:true}") boolean enabled,
      @Value("${app.rate-limit.orders.limit:20}") long limit,
      @Value("${app.rate-limit.orders.window-ms:60000}") long windowMs) {
    this.redis = redis;
    this.enabled = enabled;
    if (limit < 1 || windowMs < 1) {
      throw new IllegalArgumentException("Order rate limit and window must be positive");
    }
    this.limit = limit;
    this.windowMs = windowMs;
  }

  /** Throws a structured 429 when Redis atomically counts more than the configured limit. */
  public void check(long userId) {
    if (!enabled) {
      return;
    }
    Long count;
    try {
      count =
          redis.execute(
              INCREMENT_WITH_FIRST_HIT_EXPIRY,
              List.of(KEY_PREFIX + userId),
              Long.toString(windowMs));
    } catch (RuntimeException error) {
      if (redisFailureLogged.compareAndSet(false, true)) {
        log.warn(
            "Redis order rate limiter unavailable; allowing request (type={})",
            error.getClass().getSimpleName());
      }
      return;
    }
    if (count == null) {
      if (redisFailureLogged.compareAndSet(false, true)) {
        log.warn("Redis order rate limiter returned no counter; allowing request");
      }
      return;
    }
    redisFailureLogged.set(false);
    if (count > limit) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMIT_EXCEEDED",
          "Too many order requests; try again later");
    }
  }
}
