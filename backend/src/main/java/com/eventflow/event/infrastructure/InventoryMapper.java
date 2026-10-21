package com.eventflow.event.infrastructure;

import com.eventflow.event.domain.SkuEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InventoryMapper {
  record Location(long eventId, long sessionId) {}

  record SkuStock(long id, int availableStock) {}

  Location locate(long skuId);

  String lockEvent(long eventId);

  LocalDateTime lockSession(long sessionId);

  SkuEntity lockSku(long skuId);

  int reserve(long skuId);

  int release(@Param("skuId") long skuId);

  List<SkuStock> listAvailableStock(@Param("skuIds") List<Long> skuIds);
}
