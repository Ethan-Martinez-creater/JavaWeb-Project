package com.eventflow.event.application;

import com.eventflow.common.error.ApiException;
import com.eventflow.event.domain.SkuEntity;
import com.eventflow.event.infrastructure.InventoryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inventory operations must participate in the caller's order transaction. */
@Service
public class InventoryService {
  public record Reservation(long priceCents, Instant startsAt, Instant reservedAt) {}

  private final InventoryMapper mapper;
  private final Clock clock;

  public InventoryService(InventoryMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Reservation reserve(long skuId) {
    var location = mapper.locate(skuId);
    if (location == null) {
      throw unavailable();
    }
    // Catalog writes take the parent event's exclusive lock. Shared catalog locks
    // allow independent SKUs to sell concurrently while keeping publication stable.
    if (!"PUBLISHED".equals(mapper.lockEvent(location.eventId()))) {
      throw unavailable();
    }
    LocalDateTime startsAt = mapper.lockSession(location.sessionId());
    SkuEntity sku = mapper.lockSku(skuId);
    Instant now = clock.instant(); // Re-read trusted time after all blocking locks.
    if (startsAt == null || sku == null || !startsAt.toInstant(ZoneOffset.UTC).isAfter(now)) {
      throw unavailable();
    }
    if (mapper.reserve(skuId) != 1) {
      throw new ApiException(HttpStatus.CONFLICT, "SOLD_OUT", "No tickets are available");
    }
    return new Reservation(sku.getPriceCents(), startsAt.toInstant(ZoneOffset.UTC), now);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void release(long skuId) {
    if (mapper.release(skuId) != 1) {
      // Roll back the terminal transition as well; never silently overfill inventory.
      throw new IllegalStateException("Inventory release invariant failed");
    }
  }

  private static ApiException unavailable() {
    return new ApiException(HttpStatus.CONFLICT, "SKU_UNAVAILABLE", "Ticket SKU is unavailable");
  }
}
