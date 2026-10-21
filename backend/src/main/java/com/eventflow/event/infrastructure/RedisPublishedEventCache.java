package com.eventflow.event.infrastructure;

import com.eventflow.cache.EventCacheMetrics;
import com.eventflow.event.application.PublishedEventCacheData.Detail;
import com.eventflow.event.application.PublishedEventCacheData.Page;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Cache Aside storage for published event metadata. Redis is an optional acceleration layer. */
@Component
public class RedisPublishedEventCache {
  private static final Logger log = LoggerFactory.getLogger(RedisPublishedEventCache.class);
  private static final String PREFIX = "eventflow:events";
  private static final String GENERATION_KEY = PREFIX + ":generation";
  private static final DefaultRedisScript<Long> PUT_IF_GENERATION_MATCHES =
      new DefaultRedisScript<>(
          "if redis.call('GET', KEYS[1]) == ARGV[1] then "
              + "redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3]); return 1; "
              + "else return 0; end",
          Long.class);
  private static final DefaultRedisScript<Long> INCREMENT_GENERATION =
      new DefaultRedisScript<>("return redis.call('INCR', KEYS[1])", Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final EventCacheMetrics metrics;
  private final boolean enabled;
  private final long listTtlMs;
  private final long detailTtlMs;
  private final long ttlJitterMs;
  private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

  public RedisPublishedEventCache(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      EventCacheMetrics metrics,
      @Value("${app.events.cache.enabled:true}") boolean enabled,
      @Value("${app.events.cache.list-ttl-ms:60000}") long listTtlMs,
      @Value("${app.events.cache.detail-ttl-ms:300000}") long detailTtlMs,
      @Value("${app.events.cache.ttl-jitter-ms:15000}") long ttlJitterMs) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.enabled = enabled;
    this.listTtlMs = positive(listTtlMs, "list-ttl-ms");
    this.detailTtlMs = positive(detailTtlMs, "detail-ttl-ms");
    this.ttlJitterMs = nonNegative(ttlJitterMs, "ttl-jitter-ms");
  }

  public ListLookup readList(String keyword, int page, int size) {
    if (!enabled) {
      return new ListLookup(null, null);
    }
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        String generation = currentGeneration();
        String json = redis.opsForValue().get(listKey(generation, keyword, page, size));
        if (!generation.equals(currentGeneration())) {
          continue;
        }
        if (json == null) {
          clearRedisFailureLog();
          metrics.recordMiss();
          return new ListLookup(null, generation);
        }
        Page cached = objectMapper.readValue(json, Page.class);
        if (cached == null
            || cached.items() == null
            || cached.page() != page
            || cached.size() != size
            || cached.total() < 0) {
          throw new IllegalStateException("Invalid event list cache value");
        }
        clearRedisFailureLog();
        metrics.recordHit();
        return new ListLookup(cached, generation);
      }
      clearRedisFailureLog();
      metrics.recordMiss();
      return new ListLookup(null, null);
    } catch (RuntimeException error) {
      recordRedisFailure("read list", error);
      metrics.recordMiss();
      return new ListLookup(null, null);
    }
  }

  public void writeList(ListLookup lookup, Page page, String keyword, int pageNumber, int size) {
    if (!enabled || lookup.generation() == null) {
      return;
    }
    writeIfCurrent(
        lookup.generation(),
        listKey(lookup.generation(), keyword, pageNumber, size),
        page,
        ttl(listTtlMs));
  }

  public DetailLookup readDetail(long eventId) {
    if (!enabled) {
      return new DetailLookup(null, null);
    }
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        String generation = currentGeneration();
        String json = redis.opsForValue().get(detailKey(generation, eventId));
        if (!generation.equals(currentGeneration())) {
          continue;
        }
        if (json == null) {
          clearRedisFailureLog();
          metrics.recordMiss();
          return new DetailLookup(null, generation);
        }
        Detail cached = objectMapper.readValue(json, Detail.class);
        if (!isValidDetail(cached, eventId)) {
          throw new IllegalStateException("Invalid event detail cache value");
        }
        clearRedisFailureLog();
        metrics.recordHit();
        return new DetailLookup(cached, generation);
      }
      clearRedisFailureLog();
      metrics.recordMiss();
      return new DetailLookup(null, null);
    } catch (RuntimeException error) {
      recordRedisFailure("read detail", error);
      metrics.recordMiss();
      return new DetailLookup(null, null);
    }
  }

  public void writeDetail(DetailLookup lookup, long eventId, Detail detail) {
    if (!enabled || lookup.generation() == null) {
      return;
    }
    writeIfCurrent(
        lookup.generation(), detailKey(lookup.generation(), eventId), detail, ttl(detailTtlMs));
  }

  /** Advance the namespace only after a successful database commit. */
  public void invalidateAfterCommit() {
    if (!enabled) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              invalidateNow();
            }
          });
    } else {
      invalidateNow();
    }
  }

  private void invalidateNow() {
    try {
      currentGeneration();
      Long next = redis.execute(INCREMENT_GENERATION, List.of(GENERATION_KEY));
      if (next == null) {
        throw new IllegalStateException("Redis did not return a new cache generation");
      }
      clearRedisFailureLog();
      metrics.recordInvalidation();
    } catch (RuntimeException error) {
      recordRedisFailure("invalidate event cache", error);
    }
  }

  private void writeIfCurrent(String generation, String key, Object payload, long ttlMs) {
    try {
      String json = objectMapper.writeValueAsString(payload);
      redis.execute(
          PUT_IF_GENERATION_MATCHES,
          List.of(GENERATION_KEY, key),
          generation,
          json,
          Long.toString(ttlMs));
      clearRedisFailureLog();
    } catch (RuntimeException error) {
      recordRedisFailure("write event cache", error);
    }
  }

  private String currentGeneration() {
    String generation = redis.opsForValue().get(GENERATION_KEY);
    if (generation == null) {
      redis.opsForValue().setIfAbsent(GENERATION_KEY, "0");
      generation = redis.opsForValue().get(GENERATION_KEY);
    }
    if (generation == null) {
      throw new IllegalStateException("Redis cache generation is unavailable");
    }
    return generation;
  }

  private String listKey(String generation, String keyword, int page, int size) {
    return PREFIX
        + ":g:"
        + generation
        + ":list:"
        + page
        + ":"
        + size
        + ":"
        + sha256(keyword == null ? "" : keyword);
  }

  private String detailKey(String generation, long eventId) {
    return PREFIX + ":g:" + generation + ":detail:" + eventId;
  }

  private long ttl(long baseMs) {
    return baseMs + (ttlJitterMs == 0 ? 0 : ThreadLocalRandom.current().nextLong(ttlJitterMs + 1));
  }

  private void recordRedisFailure(String operation, RuntimeException error) {
    metrics.recordRedisFailure();
    if (redisFailureLogged.compareAndSet(false, true)) {
      log.warn(
          "Redis event cache {} failed; using MySQL fallback (type={})",
          operation,
          error.getClass().getSimpleName());
    }
  }

  private void clearRedisFailureLog() {
    redisFailureLogged.set(false);
  }

  private boolean isValidDetail(Detail detail, long eventId) {
    if (detail == null
        || detail.id() != eventId
        || !"PUBLISHED".equals(detail.status())
        || detail.sessions() == null) {
      return false;
    }
    return detail.sessions().stream()
        .allMatch(
            session ->
                session != null
                    && session.ticketSkus() != null
                    && session.ticketSkus().stream().allMatch(java.util.Objects::nonNull));
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static long positive(long value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException("app.events.cache." + name + " must be positive");
    }
    return value;
  }

  private static long nonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException("app.events.cache." + name + " must not be negative");
    }
    return value;
  }

  public record ListLookup(Page page, String generation) {}

  public record DetailLookup(Detail detail, String generation) {}
}
