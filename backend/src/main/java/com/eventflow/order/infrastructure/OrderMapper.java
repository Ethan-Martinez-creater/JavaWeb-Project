package com.eventflow.order.infrastructure;

import com.eventflow.order.domain.OrderEntity;
import com.eventflow.order.domain.OrderItemEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {
  void insertReservation(OrderEntity candidate);

  OrderEntity lockByKey(@Param("userId") long userId, @Param("key") String key);

  OrderEntity lockOwned(@Param("id") long id, @Param("userId") long userId);

  OrderEntity lockById(long id);

  OrderEntity findOwned(@Param("id") long id, @Param("userId") long userId);

  long countOwned(long userId);

  List<OrderEntity> listOwned(
      @Param("userId") long userId, @Param("offset") long offset, @Param("size") int size);

  int completeReservation(
      @Param("id") long id,
      @Param("price") long price,
      @Param("expiresAt") LocalDateTime expiresAt);

  void insertItem(OrderItemEntity item);

  OrderItemEntity findItem(long orderId);

  int markPaid(@Param("id") long id, @Param("now") LocalDateTime now);

  int markClosed(@Param("id") long id, @Param("now") LocalDateTime now);

  List<Long> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

  Integer expiryRetryAttempts(long id);

  int deferExpiry(@Param("id") long id, @Param("retryAt") LocalDateTime retryAt);
}
