package com.eventflow.order.domain;

import java.time.LocalDateTime;

public class OrderEntity {
  private long id;
  private String orderNo;
  private long userId;
  private String idempotencyKey;
  private long requestedSkuId;
  private OrderStatus status;
  private long totalPriceCents;
  private LocalDateTime expiresAt;
  private LocalDateTime paidAt;
  private LocalDateTime closedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getOrderNo() {
    return orderNo;
  }

  public void setOrderNo(String orderNo) {
    this.orderNo = orderNo;
  }

  public long getUserId() {
    return userId;
  }

  public void setUserId(long userId) {
    this.userId = userId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public long getRequestedSkuId() {
    return requestedSkuId;
  }

  public void setRequestedSkuId(long requestedSkuId) {
    this.requestedSkuId = requestedSkuId;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public void setStatus(OrderStatus status) {
    this.status = status;
  }

  public long getTotalPriceCents() {
    return totalPriceCents;
  }

  public void setTotalPriceCents(long totalPriceCents) {
    this.totalPriceCents = totalPriceCents;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getPaidAt() {
    return paidAt;
  }

  public void setPaidAt(LocalDateTime paidAt) {
    this.paidAt = paidAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
