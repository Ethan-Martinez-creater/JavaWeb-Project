package com.eventflow.event.domain;

import java.time.LocalDateTime;

public class EventEntity {
  private Long id;
  private Long organizerId;
  private String title;
  private String description;
  private EventStatus status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getOrganizerId() {
    return organizerId;
  }

  public void setOrganizerId(Long organizerId) {
    this.organizerId = organizerId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public EventStatus getStatus() {
    return status;
  }

  public void setStatus(EventStatus status) {
    this.status = status;
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
