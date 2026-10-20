package com.eventflow.ticket.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

public final class TicketDtos {
  private TicketDtos() {}

  public record TicketView(
      long id,
      long orderItemId,
      long orderId,
      long eventId,
      long sessionId,
      long skuId,
      String status,
      Instant usedAt,
      Instant createdAt) {}

  public record CheckinCodeResponse(String checkinCode) {}

  public record CheckInRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String checkinCode) {}

  public record CheckInResponse(long ticketId, String status, Instant usedAt) {}
}
