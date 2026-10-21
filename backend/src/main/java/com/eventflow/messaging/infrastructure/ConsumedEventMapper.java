package com.eventflow.messaging.infrastructure;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsumedEventMapper {
  int insertIfAbsent(
      @Param("eventId") String eventId,
      @Param("consumerName") String consumerName,
      @Param("consumedAt") LocalDateTime consumedAt);
}
