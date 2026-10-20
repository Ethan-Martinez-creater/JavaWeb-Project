package com.eventflow.order.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "app.orders.expiry-scan-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderExpirySchedule {
  private static final Logger log = LoggerFactory.getLogger(OrderExpirySchedule.class);
  private final OrderExpiryScanner scanner;

  public OrderExpirySchedule(OrderExpiryScanner scanner) {
    this.scanner = scanner;
  }

  @Scheduled(
      fixedDelayString = "${app.orders.expiry-scan-delay-ms:30000}",
      initialDelayString = "${app.orders.expiry-scan-delay-ms:30000}")
  public void scan() {
    try {
      scanner.scanOnce();
    } catch (RuntimeException error) {
      // Do not log driver messages containing SQL parameters; retry next scheduled run.
      log.error("Expiry scan failed type={}", error.getClass().getName());
    }
  }
}
