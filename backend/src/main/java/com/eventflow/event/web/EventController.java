package com.eventflow.event.web;

import com.eventflow.common.web.PageResponse;
import com.eventflow.event.application.EventService;
import com.eventflow.event.web.EventDtos.EventResponse;
import com.eventflow.event.web.EventDtos.EventSummary;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {
  private final EventService service;

  public EventController(EventService service) {
    this.service = service;
  }

  @GetMapping
  public PageResponse<EventSummary> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.published(keyword, page, size);
  }

  @GetMapping("/{id}")
  public EventResponse detail(@PathVariable @Positive long id) {
    return service.publishedDetail(id);
  }
}
