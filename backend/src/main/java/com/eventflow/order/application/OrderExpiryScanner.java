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

  public OrderExpiryScanner(OrderMapper mapper, OrderService orders, Clock clock) {
    this.mapper = mapper;
    this.orders = orders;
    this.clock = clock;
  }

  public int scanOnce() {
    int closed = 0;
    // A failed scan changes no state. A later invocation re-queries persistent pending orders.
    for (long id :
        mapper.findExpired(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), BATCH_SIZE)) {
      try {
        if (orders.closeExpired(id)) {
          closed++;
        }
      } catch (RuntimeException error) {
        log.error("Expiry close failed orderId={} type={}", id, error.getClass().getName());
      }
    }
    return closed;
  }
}
