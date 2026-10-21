package com.eventflow.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.messaging.publisher-schedule-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPublisherSchedule {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisherSchedule.class);
  private final ObjectProvider<OutboxPublisher> publisherProvider;

  public OutboxPublisherSchedule(ObjectProvider<OutboxPublisher> publisherProvider) {
    this.publisherProvider = publisherProvider;
  }

  @Scheduled(fixedDelayString = "${app.messaging.publisher-delay-ms:1000}")
  public void publish() {
    try {
      OutboxPublisher publisher = publisherProvider.getIfAvailable();
      if (publisher != null) {
        publisher.publishBatchOnce();
      }
    } catch (RuntimeException exception) {
      log.error("Outbox publisher cycle failed type={}", exception.getClass().getName());
    }
  }
}
