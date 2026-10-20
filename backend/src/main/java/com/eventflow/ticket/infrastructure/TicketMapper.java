package com.eventflow.ticket.infrastructure;

import com.eventflow.ticket.domain.TicketEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TicketMapper {
  TicketEntity findByIdForUpdate(@Param("id") long id);

  TicketEntity findByOrderItem(@Param("orderItemId") long orderItemId);

  TicketQualification findQualification(@Param("orderItemId") long orderItemId);

  TicketProjection findViewByOrderItem(@Param("orderItemId") long orderItemId);

  Long findIdByCodeHash(@Param("codeHash") String codeHash);

  List<TicketProjection> listByOwner(
      @Param("ownerUserId") long ownerUserId,
      @Param("offset") long offset,
      @Param("size") int size);

  long countByOwner(@Param("ownerUserId") long ownerUserId);

  int insertTicket(
      @Param("orderItemId") long orderItemId,
      @Param("ownerUserId") long ownerUserId,
      @Param("createdAt") LocalDateTime createdAt);

  int rotateCheckinCode(
      @Param("id") long id,
      @Param("codeHash") String codeHash,
      @Param("updatedAt") LocalDateTime updatedAt);

  int markUsed(
      @Param("id") long id,
      @Param("codeHash") String codeHash,
      @Param("usedAt") LocalDateTime usedAt);

  int insertSuccessRecord(
      @Param("ticketId") long ticketId,
      @Param("operatorUserId") long operatorUserId,
      @Param("createdAt") LocalDateTime createdAt);
}
