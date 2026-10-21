package com.eventflow.notification.infrastructure;

import com.eventflow.notification.domain.NotificationEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationMapper {
  int insert(NotificationEntity notification);

  long countOwned(@Param("userId") long userId);

  List<NotificationEntity> listOwned(
      @Param("userId") long userId, @Param("offset") long offset, @Param("size") int size);
}
