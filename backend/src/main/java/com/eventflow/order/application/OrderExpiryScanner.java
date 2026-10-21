package com.eventflow.order.application;

import com.eventflow.order.infrastructure.OrderMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryScanner {
  private static final Logger log = LoggerFactory.getLogger(OrderExpiryScanner.class);
  private static final int BATCH_SIZE = 100;
  private final OrderMapper mapper;
  private final OrderService orders;
  private final Clock clock;
  private final OrderExpiryRetryService retries;

  public OrderExpiryScanner(
      OrderMapper mapper, OrderService orders, Clock clock, OrderExpiryRetryService retries) {
    this.mapper = mapper;
    this.orders = orders;
    this.clock = clock;
    this.retries = retries;
  }

  public int scanOnce() {
    int closed = 0;
    // Failed rows are deferred persistently, so the earliest poison batch cannot starve later rows.
    for (long id :
        mapper.findExpired(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), BATCH_SIZE)) {
      try {
        if (orders.closeExpired(id)) {
          closed++;
        }
      } catch (RuntimeException error) {
        log.error("Expiry close failed orderId={} type={}", id, error.getClass().getName());
        try {
          retries.defer(id);
        } catch (RuntimeException retryError) {
          log.error(
              "Expiry retry persistence failed orderId={} type={}",
              id,
              retryError.getClass().getName());
        }
      }
    }
    return closed;
  }
}
