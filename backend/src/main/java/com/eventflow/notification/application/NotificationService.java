package com.eventflow.notification.application;

import com.eventflow.common.error.ApiException;
import com.eventflow.common.web.PageResponse;
import com.eventflow.notification.domain.NotificationEntity;
import com.eventflow.notification.infrastructure.NotificationMapper;
import com.eventflow.notification.web.NotificationDtos.NotificationView;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final NotificationMapper mapper;

  public NotificationService(NotificationMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public PageResponse<NotificationView> listMine(long userId, int page, int size) {
    if (userId <= 0 || page < 0 || size < 1 || size > 100) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request");
    }
    long total = mapper.countOwned(userId);
    List<NotificationView> items =
        mapper.listOwned(userId, (long) page * size, size).stream().map(this::view).toList();
    return new PageResponse<>(items, page, size, total);
  }

  private NotificationView view(NotificationEntity notification) {
    return new NotificationView(
        notification.getId(),
        notification.getOrderId(),
        notification.getEventType(),
        notification.getMessage(),
        notification.getCreatedAt().toInstant(ZoneOffset.UTC));
  }
}
