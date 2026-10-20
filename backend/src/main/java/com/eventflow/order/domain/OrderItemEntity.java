package com.eventflow.order.domain;

public class OrderItemEntity {
  private long id;
  private long orderId;
  private long skuId;
  private long priceCents;
  private int quantity;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public long getOrderId() {
    return orderId;
  }

  public void setOrderId(long orderId) {
    this.orderId = orderId;
  }

  public long getSkuId() {
    return skuId;
  }

  public void setSkuId(long skuId) {
    this.skuId = skuId;
  }

  public long getPriceCents() {
    return priceCents;
  }

  public void setPriceCents(long priceCents) {
    this.priceCents = priceCents;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }
}
