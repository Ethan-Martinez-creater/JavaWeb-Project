package com.eventflow.order.application;

import com.eventflow.common.error.ApiException;
import com.eventflow.common.web.PageResponse;
import com.eventflow.event.application.InventoryService;
import com.eventflow.order.domain.OrderEntity;
import com.eventflow.order.domain.OrderItemEntity;
import com.eventflow.order.domain.OrderStatus;
import com.eventflow.order.infrastructure.OrderMapper;
import com.eventflow.order.web.OrderDtos.OrderItemView;
import com.eventflow.order.web.OrderDtos.OrderView;
import com.eventflow.ticket.application.TicketService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
  public record CreateResult(OrderView order, boolean created) {}

  private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);
  private final OrderMapper mapper;
  private final InventoryService inventory;
  private final TicketService tickets;
  private final Clock clock;

  public OrderService(
      OrderMapper mapper, InventoryService inventory, TicketService tickets, Clock clock) {
    this.mapper = mapper;
    this.inventory = inventory;
    this.tickets = tickets;
    this.clock = clock;
  }

  @Transactional
  public CreateResult create(long userId, long skuId, String key) {
    if (userId <= 0 || skuId <= 0 || key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request");
    }
    var candidate = new OrderEntity();
    candidate.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
    candidate.setUserId(userId);
    candidate.setIdempotencyKey(key);
    candidate.setRequestedSkuId(skuId);
    candidate.setExpiresAt(utc(clock.instant().plus(PAYMENT_WINDOW)));

    // A unique-key insert is the first synchronization point, not SELECT-then-INSERT.
    // A competing retry waits for commit/rollback before observing the winner.
    mapper.insertReservation(candidate);
    OrderEntity order = mapper.lockByKey(userId, key);
    if (order == null) {
      throw new IllegalStateException("Idempotency reservation invariant failed");
    }
    if (order.getRequestedSkuId() != skuId) {
      throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency key was used for different parameters");
    }
    if (!order.getOrderNo().equals(candidate.getOrderNo())) {
      // This includes PAID/CLOSED orders: an old key never purchases a new ticket.
      return new CreateResult(view(order), false);
    }

    var reservation = inventory.reserve(skuId);
    Instant expiry = reservation.reservedAt().plus(PAYMENT_WINDOW);
    if (reservation.startsAt().isBefore(expiry)) {
      expiry = reservation.startsAt();
    }
    if (mapper.completeReservation(order.getId(), reservation.priceCents(), utc(expiry)) != 1) {
      throw new IllegalStateException("Order reservation invariant failed");
    }
    var item = new OrderItemEntity();
    item.setOrderId(order.getId());
    item.setSkuId(skuId);
    item.setPriceCents(reservation.priceCents());
    item.setQuantity(1);
    mapper.insertItem(item);
    return new CreateResult(view(mapper.findOwned(order.getId(), userId)), true);
  }

  @Transactional(readOnly = true)
  public PageResponse<OrderView> list(long userId, int page, int size) {
    validatePage(page, size);
    long count = mapper.countOwned(userId);
    var items =
        mapper.listOwned(userId, (long) page * size, size).stream().map(this::view).toList();
    return new PageResponse<>(items, page, size, count);
  }

  @Transactional(readOnly = true)
  public OrderView detail(long id, long userId) {
    return view(requireOwned(mapper.findOwned(id, userId)));
  }

  @Transactional
  public OrderView pay(long id, long userId) {
    OrderEntity order = requireOwned(mapper.lockOwned(id, userId));
    if (order.getStatus() == OrderStatus.PAID) {
      return view(order);
    }
    LocalDateTime now = utc(clock.instant());
    if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.getExpiresAt().isAfter(now)) {
      // Reject expiry here without mutation; the retryable scanner owns expiry compensation.
      throw conflict("ORDER_NOT_PAYABLE", "Order cannot be paid");
    }
    if (mapper.markPaid(id, now) != 1) {
      throw conflict("ORDER_NOT_PAYABLE", "Order cannot be paid");
    }
    OrderItemEntity item = requireItem(id);
    tickets.issue(item.getId(), userId, now.toInstant(ZoneOffset.UTC));
    return view(mapper.findOwned(id, userId));
  }

  @Transactional
  public OrderView cancel(long id, long userId) {
    OrderEntity order = requireOwned(mapper.lockOwned(id, userId));
    if (order.getStatus() == OrderStatus.CLOSED) {
      return view(order);
    }
    if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
      throw conflict("ORDER_NOT_CANCELLABLE", "Order cannot be cancelled");
    }
    closeLocked(order, utc(clock.instant()));
    return view(mapper.findOwned(id, userId));
  }

  /** Called through the Spring proxy by the scanner; every order has its own transaction. */
  @Transactional
  public boolean closeExpired(long id) {
    OrderEntity order = mapper.lockById(id);
    LocalDateTime now = utc(clock.instant());
    if (order == null
        || order.getStatus() != OrderStatus.PENDING_PAYMENT
        || order.getExpiresAt().isAfter(now)) {
      return false;
    }
    closeLocked(order, now);
    return true;
  }

  private void closeLocked(OrderEntity order, LocalDateTime now) {
    // Every terminal transition starts with the order lock, then inventory or ticket writes.
    if (mapper.markClosed(order.getId(), now) != 1) {
      throw new IllegalStateException("Order close invariant failed");
    }
    inventory.release(requireItem(order.getId()).getSkuId());
  }

  private OrderView view(OrderEntity order) {
    OrderItemEntity item = requireItem(order.getId());
    var ticket = tickets.forOrderItem(item.getId());
    if (order.getStatus() == OrderStatus.PAID && ticket == null) {
      throw new IllegalStateException("Paid order ticket invariant failed");
    }
    return new OrderView(
        order.getId(),
        order.getOrderNo(),
        order.getStatus().name(),
        order.getTotalPriceCents(),
        instant(order.getExpiresAt()),
        instant(order.getPaidAt()),
        instant(order.getClosedAt()),
        instant(order.getCreatedAt()),
        new OrderItemView(item.getId(), item.getSkuId(), item.getPriceCents(), item.getQuantity()),
        ticket);
  }

  private OrderItemEntity requireItem(long orderId) {
    OrderItemEntity item = mapper.findItem(orderId);
    if (item == null) {
      throw new IllegalStateException("Order item invariant failed");
    }
    return item;
  }

  private static OrderEntity requireOwned(OrderEntity order) {
    if (order == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }
    return order;
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }

  private static void validatePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request");
    }
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
