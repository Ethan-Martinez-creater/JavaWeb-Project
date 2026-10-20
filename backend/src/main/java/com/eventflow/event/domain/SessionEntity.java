package com.eventflow.event.domain;

import java.time.LocalDateTime;

public class SessionEntity {
  private Long id;
  private Long eventId;
  private String title;
  private LocalDateTime startsAt;
  private LocalDateTime endsAt;
  private String location;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getEventId() {
    return eventId;
  }

  public void setEventId(Long eventId) {
    this.eventId = eventId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
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

  public String getLocation() {
    return location;
  }

  public void setLocation(String location) {
    this.location = location;
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
