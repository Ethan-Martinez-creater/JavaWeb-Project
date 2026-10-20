package com.eventflow.event.application;

import com.eventflow.common.error.ApiException;
import com.eventflow.common.web.PageResponse;
import com.eventflow.event.domain.EventEntity;
import com.eventflow.event.domain.EventStatus;
import com.eventflow.event.domain.SessionEntity;
import com.eventflow.event.domain.SkuEntity;
import com.eventflow.event.infrastructure.EventMapper;
import com.eventflow.event.web.EventDtos.CreateEvent;
import com.eventflow.event.web.EventDtos.CreateSession;
import com.eventflow.event.web.EventDtos.CreateSku;
import com.eventflow.event.web.EventDtos.EditEvent;
import com.eventflow.event.web.EventDtos.EventResponse;
import com.eventflow.event.web.EventDtos.EventSummary;
import com.eventflow.event.web.EventDtos.SessionResponse;
import com.eventflow.event.web.EventDtos.SkuResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  private final EventMapper mapper;
  private final Clock clock;

  public EventService(EventMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<EventSummary> published(String keyword, int page, int size) {
    validatePage(page, size);
    if (keyword != null && keyword.length() > 120) {
      throw invalid();
    }
    String escaped =
        keyword == null
            ? ""
            : keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    long total = mapper.countPublished(escaped);
    var items =
        mapper.listPublished(escaped, (long) page * size, size).stream()
            .map(this::summary)
            .toList();
    return new PageResponse<>(items, page, size, total);
  }

  @Transactional(readOnly = true)
  public EventResponse publishedDetail(long id) {
    EventEntity event = mapper.findPublished(id);
    if (event == null) {
      throw missing();
    }
    return detail(event);
  }

  @Transactional(readOnly = true)
  public PageResponse<EventSummary> owned(long ownerId, int page, int size) {
    validatePage(page, size);
    long total = mapper.countOwned(ownerId);
    var items =
        mapper.listOwned(ownerId, (long) page * size, size).stream().map(this::summary).toList();
    return new PageResponse<>(items, page, size, total);
  }

  @Transactional
  public EventResponse create(long ownerId, CreateEvent input) {
    EventEntity event = new EventEntity();
    event.setOrganizerId(ownerId);
    event.setTitle(input.title().strip());
    event.setDescription(input.description());
    event.setStatus(EventStatus.DRAFT);
    mapper.insertEvent(event);
    return detail(mapper.findOwned(event.getId(), ownerId, false));
  }

  @Transactional
  public EventResponse edit(long id, long ownerId, boolean admin, EditEvent input) {
    EventEntity event = draft(id, ownerId, admin);
    if (input.title() == null && input.description() == null) {
      throw invalid();
    }
    if (input.title() != null) {
      if (input.title().isBlank()) {
        throw invalid();
      }
      event.setTitle(input.title().strip());
    }
    if (input.description() != null) {
      event.setDescription(input.description());
    }
    mapper.updateDraft(event);
    return detail(mapper.findOwned(id, ownerId, admin));
  }

  @Transactional
  public SessionResponse addSession(
      long eventId, long ownerId, boolean admin, CreateSession input) {
    draft(eventId, ownerId, admin);
    LocalDateTime start = utc(input.startsAt().toInstant()).truncatedTo(ChronoUnit.MICROS);
    LocalDateTime end = utc(input.endsAt().toInstant()).truncatedTo(ChronoUnit.MICROS);
    // Reject values outside MySQL DATETIME's range rather than turning them into SQL errors.
    if (!start.isBefore(end) || start.getYear() < 1000 || end.getYear() > 9999) {
      throw invalid();
    }
    SessionEntity session = new SessionEntity();
    session.setEventId(eventId);
    session.setTitle(input.title().strip());
    session.setStartsAt(start);
    session.setEndsAt(end);
    session.setLocation(input.location().strip());
    mapper.insertSession(session);
    return sessionResponse(session, List.of());
  }

  @Transactional
  public SkuResponse addSku(long sessionId, long ownerId, boolean admin, CreateSku input) {
    SessionEntity session = mapper.findSession(sessionId);
    if (session == null) {
      throw missing();
    }
    // Every child mutation locks the same parent row as publication, preventing publish/edit races.
    draft(session.getEventId(), ownerId, admin);
    SkuEntity sku = new SkuEntity();
    sku.setSessionId(sessionId);
    sku.setName(input.name().strip());
    sku.setPriceCents(input.priceCents());
    sku.setTotalStock(input.totalStock());
    sku.setAvailableStock(input.totalStock());
    mapper.insertSku(sku);
    return skuResponse(sku);
  }

  @Transactional
  public EventResponse publish(long id, long ownerId, boolean admin) {
    EventEntity event = draft(id, ownerId, admin);
    List<SessionEntity> sessions = mapper.listSessions(id);
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    boolean hasFuture = false;
    if (sessions.isEmpty()) {
      throw conflict("At least one session and ticket SKU are required");
    }
    for (SessionEntity session : sessions) {
      if (!session.getStartsAt().isBefore(session.getEndsAt())) {
        throw conflict("Invalid session times");
      }
      hasFuture |= session.getStartsAt().isAfter(now);
      List<SkuEntity> skus = mapper.listSkus(session.getId());
      if (skus.isEmpty()) {
        throw conflict("Every session requires a ticket SKU");
      }
      for (SkuEntity sku : skus) {
        if (sku.getPriceCents() < 0
            || sku.getTotalStock() < 0
            || sku.getAvailableStock() != sku.getTotalStock()) {
          throw conflict("Invalid ticket SKU");
        }
      }
    }
    if (!hasFuture) {
      throw conflict("At least one session must start in the future");
    }
    if (mapper.publish(event.getId()) != 1) {
      throw conflict("Only drafts can be published");
    }
    return detail(mapper.findOwned(id, ownerId, admin));
  }

  private EventEntity draft(long id, long ownerId, boolean admin) {
    EventEntity event = mapper.lockOwned(id, ownerId, admin);
    if (event == null) {
      throw missing();
    }
    if (event.getStatus() != EventStatus.DRAFT) {
      throw conflict("Only drafts can be changed");
    }
    return event;
  }

  private EventResponse detail(EventEntity event) {
    var sessions =
        mapper.listSessions(event.getId()).stream()
            .map(
                session ->
                    sessionResponse(
                        session,
                        mapper.listSkus(session.getId()).stream().map(this::skuResponse).toList()))
            .toList();
    return new EventResponse(
        event.getId(),
        event.getOrganizerId(),
        event.getTitle(),
        event.getDescription(),
        event.getStatus().name(),
        instant(event.getCreatedAt()),
        instant(event.getUpdatedAt()),
        sessions);
  }

  private EventSummary summary(EventEntity event) {
    return new EventSummary(
        event.getId(),
        event.getOrganizerId(),
        event.getTitle(),
        event.getDescription(),
        event.getStatus().name(),
        instant(event.getCreatedAt()),
        instant(event.getUpdatedAt()));
  }

  private SessionResponse sessionResponse(SessionEntity session, List<SkuResponse> skus) {
    return new SessionResponse(
        session.getId(),
        session.getEventId(),
        session.getTitle(),
        instant(session.getStartsAt()),
        instant(session.getEndsAt()),
        session.getLocation(),
        skus);
  }

  private SkuResponse skuResponse(SkuEntity sku) {
    return new SkuResponse(
        sku.getId(),
        sku.getSessionId(),
        sku.getName(),
        sku.getPriceCents(),
        sku.getTotalStock(),
        sku.getAvailableStock());
  }

  private static Instant instant(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC);
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private void validatePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw invalid();
    }
  }

  private static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request");
  }

  private static ApiException missing() {
    // Identical response for missing and non-owned resources, including child routes.
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
  }

  private static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "INVALID_EVENT_STATE", message);
  }
}
