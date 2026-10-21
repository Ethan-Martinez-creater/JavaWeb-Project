package com.eventflow.cache;

import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** Counters for the public event cache path, exposed for diagnostics and integration tests. */
@Component
public class EventCacheMetrics {
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder invalidations = new LongAdder();
  private final LongAdder redisFailures = new LongAdder();

  public Snapshot snapshot() {
    return new Snapshot(hits.sum(), misses.sum(), invalidations.sum(), redisFailures.sum());
  }

  public void reset() {
    hits.reset();
    misses.reset();
    invalidations.reset();
    redisFailures.reset();
  }

  public void recordHit() {
    hits.increment();
  }

  public void recordMiss() {
    misses.increment();
  }

  public void recordInvalidation() {
    invalidations.increment();
  }

  public void recordRedisFailure() {
    redisFailures.increment();
  }

  public record Snapshot(long hits, long misses, long invalidations, long redisFailures) {}
}
