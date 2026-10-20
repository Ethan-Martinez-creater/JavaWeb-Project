package com.eventflow.event.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public final class EventDtos {
  private EventDtos() {}

  public record CreateEvent(
      @NotBlank @Size(max = 120) String title, @NotNull @Size(max = 10000) String description) {}

  public record EditEvent(
      @Size(min = 1, max = 120) String title, @Size(max = 10000) String description) {}

  public record CreateSession(
      @NotBlank @Size(max = 120) String title,
      @NotNull OffsetDateTime startsAt,
      @NotNull OffsetDateTime endsAt,
      @NotBlank @Size(max = 160) String location) {}

  public record CreateSku(
      @NotBlank @Size(max = 100) String name,
      @NotNull @PositiveOrZero Long priceCents,
      @NotNull @PositiveOrZero Integer totalStock) {}

  public record EventSummary(
      long id,
      long organizerId,
      String title,
      String description,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  public record EventResponse(
      long id,
      long organizerId,
      String title,
      String description,
      String status,
      Instant createdAt,
      Instant updatedAt,
      List<SessionResponse> sessions) {}

  public record SessionResponse(
      long id,
      long eventId,
      String title,
      Instant startsAt,
      Instant endsAt,
      String location,
      List<SkuResponse> ticketSkus) {}

  public record SkuResponse(
      long id, long sessionId, String name, long priceCents, int totalStock, int availableStock) {}
}
