package com.eventflow.event.infrastructure;

import com.eventflow.event.domain.EventEntity;
import com.eventflow.event.domain.SessionEntity;
import com.eventflow.event.domain.SkuEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EventMapper {
  void insertEvent(EventEntity event);

  EventEntity findPublished(@Param("id") long id);

  EventEntity findOwned(
      @Param("id") long id, @Param("ownerId") long ownerId, @Param("admin") boolean admin);

  EventEntity lockOwned(
      @Param("id") long id, @Param("ownerId") long ownerId, @Param("admin") boolean admin);

  int updateDraft(EventEntity event);

  int publish(@Param("id") long id);

  List<EventEntity> listPublished(
      @Param("keyword") String keyword, @Param("offset") long offset, @Param("size") int size);

  long countPublished(@Param("keyword") String keyword);

  List<EventEntity> listOwned(
      @Param("ownerId") long ownerId, @Param("offset") long offset, @Param("size") int size);

  long countOwned(@Param("ownerId") long ownerId);

  void insertSession(SessionEntity session);

  SessionEntity findSession(@Param("id") long id);

  List<SessionEntity> listSessions(@Param("eventId") long eventId);

  void insertSku(SkuEntity sku);

  List<SkuEntity> listSkus(@Param("sessionId") long sessionId);
}
