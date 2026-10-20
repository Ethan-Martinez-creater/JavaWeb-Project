package com.eventflow.event.infrastructure;

import com.eventflow.event.domain.SkuEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InventoryMapper {
  record Location(long eventId, long sessionId) {}

  Location locate(long skuId);

  String lockEvent(long eventId);

  LocalDateTime lockSession(long sessionId);

  SkuEntity lockSku(long skuId);

  int reserve(long skuId);

  int release(@Param("skuId") long skuId);
}
