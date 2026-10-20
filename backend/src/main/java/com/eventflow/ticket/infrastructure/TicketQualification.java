package com.eventflow.ticket.infrastructure;

import java.time.LocalDateTime;

/** Internal projection used to verify paid ownership and the event check-in window. */
public class TicketQualification {
  private Long orderItemId;
  private Long orderId;
  private Long ownerUserId;
  private Long eventId;
  private Long organizerId;
  private Long sessionId;
  private Long skuId;
  private Integer quantity;
  private String orderStatus;
  private LocalDateTime startsAt;
  private LocalDateTime endsAt;

  public Long getOrderItemId() {
    return orderItemId;
  }

  public void setOrderItemId(Long orderItemId) {
    this.orderItemId = orderItemId;
  }

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long orderId) {
    this.orderId = orderId;
  }

  public Long getOwnerUserId() {
    return ownerUserId;
  }

  public void setOwnerUserId(Long ownerUserId) {
    this.ownerUserId = ownerUserId;
  }

  public Long getEventId() {
    return eventId;
  }

  public void setEventId(Long eventId) {
    this.eventId = eventId;
  }

  public Long getOrganizerId() {
    return organizerId;
  }

  public void setOrganizerId(Long organizerId) {
    this.organizerId = organizerId;
  }

  public Long getSessionId() {
    return sessionId;
  }

  public void setSessionId(Long sessionId) {
    this.sessionId = sessionId;
  }

  public Long getSkuId() {
    return skuId;
  }

  public void setSkuId(Long skuId) {
    this.skuId = skuId;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public String getOrderStatus() {
    return orderStatus;
  }

  public void setOrderStatus(String orderStatus) {
    this.orderStatus = orderStatus;
  }

  public LocalDateTime getStartsAt() {
    return startsAt;
  }

  public void setStartsAt(LocalDateTime startsAt) {
    this.startsAt = startsAt;
  }

  public LocalDateTime getEndsAt() {
    return endsAt;
  }

  public void setEndsAt(LocalDateTime endsAt) {
    this.endsAt = endsAt;
  }
}
