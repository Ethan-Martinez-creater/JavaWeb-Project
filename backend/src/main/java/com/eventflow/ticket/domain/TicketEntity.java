package com.eventflow.ticket.domain;

import java.time.LocalDateTime;

/** Mutable persistence representation. Never return this type from a controller. */
public class TicketEntity {
  private Long id;
  private Long orderItemId;
  private Long ownerUserId;
  private String checkinCodeHash;
  private TicketStatus status;
  private LocalDateTime usedAt;
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getOrderItemId() {
    return orderItemId;
  }

  public void setOrderItemId(Long orderItemId) {
    this.orderItemId = orderItemId;
  }

  public Long getOwnerUserId() {
    return ownerUserId;
  }

  public void setOwnerUserId(Long ownerUserId) {
    this.ownerUserId = ownerUserId;
  }

  public String getCheckinCodeHash() {
    return checkinCodeHash;
  }

  public void setCheckinCodeHash(String checkinCodeHash) {
    this.checkinCodeHash = checkinCodeHash;
  }

  public TicketStatus getStatus() {
    return status;
  }

  public void setStatus(TicketStatus status) {
    this.status = status;
  }

  public LocalDateTime getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(LocalDateTime usedAt) {
    this.usedAt = usedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
