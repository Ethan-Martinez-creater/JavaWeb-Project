package com.eventflow.event.application;

import com.eventflow.event.web.EventDtos.EventResponse;
import com.eventflow.event.web.EventDtos.EventSummary;
import com.eventflow.event.web.EventDtos.SessionResponse;
import com.eventflow.event.web.EventDtos.SkuResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Redis payloads deliberately omit availableStock; MySQL supplies it on every detail read. */
public final class PublishedEventCacheData {
  private PublishedEventCacheData() {}

  public record Page(List<EventSummary> items, int page, int size, long total) {}

  public record Detail(
      long id,
      long organizerId,
      String title,
      String description,
      String status,
      Instant createdAt,
      Instant updatedAt,
      List<CachedSession> sessions) {
    public static Detail from(EventResponse event) {
      return new Detail(
          event.id(),
          event.organizerId(),
          event.title(),
          event.description(),
          event.status(),
          event.createdAt(),
          event.updatedAt(),
          event.sessions().stream().map(CachedSession::from).toList());
    }

    public List<Long> skuIds() {
      return sessions.stream()
          .flatMap(session -> session.ticketSkus().stream())
          .map(CachedSku::id)
          .toList();
    }

    public EventResponse toResponse(Map<Long, Integer> availableStock) {
      return new EventResponse(
          id,
          organizerId,
          title,
          description,
          status,
          createdAt,
          updatedAt,
          sessions.stream().map(session -> session.toResponse(availableStock)).toList());
    }
  }

  public record CachedSession(
      long id,
      long eventId,
      String title,
      Instant startsAt,
      Instant endsAt,
      String location,
      List<CachedSku> ticketSkus) {
    static CachedSession from(SessionResponse session) {
      return new CachedSession(
          session.id(),
          session.eventId(),
          session.title(),
          session.startsAt(),
          session.endsAt(),
          session.location(),
          session.ticketSkus().stream().map(CachedSku::from).toList());
    }

    SessionResponse toResponse(Map<Long, Integer> availableStock) {
      return new SessionResponse(
          id,
          eventId,
          title,
          startsAt,
          endsAt,
          location,
          ticketSkus.stream().map(sku -> sku.toResponse(availableStock)).toList());
    }
  }

  public record CachedSku(long id, long sessionId, String name, long priceCents, int totalStock) {
    static CachedSku from(SkuResponse sku) {
      return new CachedSku(
          sku.id(), sku.sessionId(), sku.name(), sku.priceCents(), sku.totalStock());
    }

    SkuResponse toResponse(Map<Long, Integer> availableStock) {
      return new SkuResponse(
          id, sessionId, name, priceCents, totalStock, availableStock.getOrDefault(id, 0));
    }
  }
}
