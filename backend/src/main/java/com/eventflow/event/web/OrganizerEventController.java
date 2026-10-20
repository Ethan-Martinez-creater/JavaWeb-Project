package com.eventflow.event.web;

import com.eventflow.common.web.PageResponse;
import com.eventflow.event.application.EventService;
import com.eventflow.event.web.EventDtos.CreateEvent;
import com.eventflow.event.web.EventDtos.CreateSession;
import com.eventflow.event.web.EventDtos.CreateSku;
import com.eventflow.event.web.EventDtos.EditEvent;
import com.eventflow.event.web.EventDtos.EventResponse;
import com.eventflow.event.web.EventDtos.EventSummary;
import com.eventflow.event.web.EventDtos.SessionResponse;
import com.eventflow.event.web.EventDtos.SkuResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizer")
public class OrganizerEventController {
  private final EventService service;

  public OrganizerEventController(EventService service) {
    this.service = service;
  }

  @GetMapping("/events")
  public PageResponse<EventSummary> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.owned(userId(jwt), page, size);
  }

  @PostMapping("/events")
  @ResponseStatus(HttpStatus.CREATED)
  public EventResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateEvent input) {
    return service.create(userId(jwt), input);
  }

  @PatchMapping("/events/{id}")
  public EventResponse edit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long id,
      @Valid @RequestBody EditEvent input) {
    return service.edit(id, userId(jwt), admin(jwt), input);
  }

  @PostMapping("/events/{eventId}/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  public SessionResponse addSession(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long eventId,
      @Valid @RequestBody CreateSession input) {
    return service.addSession(eventId, userId(jwt), admin(jwt), input);
  }

  @PostMapping("/sessions/{sessionId}/ticket-skus")
  @ResponseStatus(HttpStatus.CREATED)
  public SkuResponse addSku(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long sessionId,
      @Valid @RequestBody CreateSku input) {
    return service.addSku(sessionId, userId(jwt), admin(jwt), input);
  }

  @PostMapping("/events/{eventId}/publish")
  public EventResponse publish(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long eventId) {
    return service.publish(eventId, userId(jwt), admin(jwt));
  }

  private static long userId(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }

  private static boolean admin(Jwt jwt) {
    return jwt.getClaimAsStringList("roles").contains("ADMIN");
  }
}
