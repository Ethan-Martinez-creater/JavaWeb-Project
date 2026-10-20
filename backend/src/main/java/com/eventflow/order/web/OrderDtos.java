package com.eventflow.order.web;

import com.eventflow.ticket.web.TicketDtos.TicketView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public final class OrderDtos {
  private OrderDtos() {}

  public record CreateOrder(
      @NotNull @Positive Long skuId,
      @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String idempotencyKey) {}

  public record OrderItemView(long id, long skuId, long priceCents, int quantity) {}

  public record OrderView(
      long id,
      String orderNo,
      String status,
      long totalPriceCents,
      Instant expiresAt,
      Instant paidAt,
      Instant closedAt,
      Instant createdAt,
      OrderItemView item,
      TicketView ticket) {}
}
