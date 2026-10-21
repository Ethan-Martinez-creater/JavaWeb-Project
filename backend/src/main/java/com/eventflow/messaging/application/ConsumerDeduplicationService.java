package com.eventflow.messaging.application;

import com.eventflow.messaging.infrastructure.ConsumedEventMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claims a consumer/event pair within the caller's transaction. */
@Service
public class ConsumerDeduplicationService {
  private final ConsumedEventMapper mapper;

  public ConsumerDeduplicationService(ConsumedEventMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryClaim(String eventId, String consumerName, LocalDateTime now) {
    return mapper.insertIfAbsent(eventId, consumerName, now) == 1;
  }
}
