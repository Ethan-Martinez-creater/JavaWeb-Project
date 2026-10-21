package com.eventflow.messaging.infrastructure;

import com.eventflow.messaging.domain.OutboxEventEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
  int insert(OutboxEventEntity event);

  OutboxEventEntity findByEventId(@Param("eventId") String eventId);

  OutboxEventEntity lockNextDue(@Param("now") LocalDateTime now);

  int claim(
      @Param("eventId") String eventId,
      @Param("owner") String owner,
      @Param("now") LocalDateTime now,
      @Param("leaseUntil") LocalDateTime leaseUntil);

  int markSent(
      @Param("eventId") String eventId,
      @Param("owner") String owner,
      @Param("publishedAt") LocalDateTime publishedAt);

  int markFailure(
      @Param("eventId") String eventId,
      @Param("owner") String owner,
      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
      @Param("errorCode") String errorCode);
}
