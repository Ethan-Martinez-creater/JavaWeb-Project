package com.eventflow.ticket.application;

import com.eventflow.common.error.ApiException;
import com.eventflow.common.web.PageResponse;
import com.eventflow.ticket.domain.TicketEntity;
import com.eventflow.ticket.domain.TicketStatus;
import com.eventflow.ticket.infrastructure.TicketMapper;
import com.eventflow.ticket.infrastructure.TicketProjection;
import com.eventflow.ticket.infrastructure.TicketQualification;
import com.eventflow.ticket.web.TicketDtos.CheckInResponse;
import com.eventflow.ticket.web.TicketDtos.CheckinCodeResponse;
import com.eventflow.ticket.web.TicketDtos.TicketView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
  private static final String PAID = "PAID";
  private static final Duration CHECKIN_OPENS_BEFORE = Duration.ofHours(2);
  private static final Duration CHECKIN_CLOSES_AFTER = Duration.ofHours(1);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final TicketMapper mapper;
  private final Clock clock;

  public TicketService(TicketMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void issue(long orderItemId, long ownerId, Instant now) {
    Objects.requireNonNull(now, "now");
    TicketQualification qualification = mapper.findQualification(orderItemId);
    if (qualification == null
        || !Objects.equals(qualification.getOwnerUserId(), ownerId)
        || !PAID.equals(qualification.getOrderStatus())
        || !Integer.valueOf(1).equals(qualification.getQuantity())) {
      throw new IllegalStateException("A ticket can only be issued for the owner's paid single-item order.");
    }

    TicketEntity existing = mapper.findByOrderItem(orderItemId);
    if (existing != null) {
      if (Objects.equals(existing.getOwnerUserId(), ownerId)) {
        return;
      }
      throw new IllegalStateException("The order item already has a ticket for another owner.");
    }

    mapper.insertTicket(orderItemId, ownerId, utc(now));
  }

  @Transactional(readOnly = true)
  public TicketView forOrderItem(long orderItemId) {
    TicketProjection projection = mapper.findViewByOrderItem(orderItemId);
    return projection == null ? null : view(projection);
  }

  @Transactional(readOnly = true)
  public PageResponse<TicketView> listMine(long ownerId, int page, int size) {
    validatePage(page, size);
    long total = mapper.countByOwner(ownerId);
    List<TicketView> items =
        mapper.listByOwner(ownerId, (long) page * size, size).stream().map(TicketService::view).toList();
    return new PageResponse<>(items, page, size, total);
  }

  @Transactional
  public CheckinCodeResponse rotateCheckinCode(long ticketId, long ownerId) {
    TicketEntity ticket = mapper.findByIdForUpdate(ticketId);
    if (ticket == null || !Objects.equals(ticket.getOwnerUserId(), ownerId)) {
      throw notFound();
    }
    TicketQualification qualification = mapper.findQualification(ticket.getOrderItemId());
    if (qualification == null
        || !Objects.equals(qualification.getOwnerUserId(), ownerId)
        || !Objects.equals(ticket.getOwnerUserId(), qualification.getOwnerUserId())) {
      throw notFound();
    }
    requirePaidUnused(ticket, qualification);

    String code = newCode();
    if (mapper.rotateCheckinCode(ticketId, hash(code), utc(clock.instant())) != 1) {
      throw conflict("The ticket is no longer eligible for a check-in code.");
    }
    return new CheckinCodeResponse(code);
  }

  @Transactional
  public CheckInResponse checkIn(long operatorUserId, boolean admin, String checkinCode) {
    if (!isCanonicalCode(checkinCode)) {
      throw invalidCode();
    }
    String codeHash = hash(checkinCode);
    Long ticketId = mapper.findIdByCodeHash(codeHash);
    if (ticketId == null) {
      throw notFound();
    }

    TicketEntity ticket = mapper.findByIdForUpdate(ticketId);
    if (ticket == null || !codeHash.equals(ticket.getCheckinCodeHash())) {
      // A concurrent rotation may have invalidated the code while this request waited for the lock.
      throw notFound();
    }
    TicketQualification qualification = mapper.findQualification(ticket.getOrderItemId());
    if (qualification == null
        || !Objects.equals(ticket.getOwnerUserId(), qualification.getOwnerUserId())) {
      throw notFound();
    }
    if (!admin && !Objects.equals(qualification.getOrganizerId(), operatorUserId)) {
      throw notFound();
    }
    requirePaidUnused(ticket, qualification);

    Instant now = clock.instant();
    Instant opensAt =
        qualification.getStartsAt().toInstant(ZoneOffset.UTC).minus(CHECKIN_OPENS_BEFORE);
    Instant closesAt =
        qualification.getEndsAt().toInstant(ZoneOffset.UTC).plus(CHECKIN_CLOSES_AFTER);
    if (now.isBefore(opensAt) || now.isAfter(closesAt)) {
      throw conflict("The ticket is outside its check-in window.");
    }

    LocalDateTime usedAt = utc(now);
    if (mapper.markUsed(ticketId, codeHash, usedAt) != 1) {
      throw conflict("The ticket has already been checked in.");
    }
    mapper.insertSuccessRecord(ticketId, operatorUserId, usedAt);
    return new CheckInResponse(ticketId, TicketStatus.USED.name(), instant(usedAt));
  }

  private static void requirePaidUnused(TicketEntity ticket, TicketQualification qualification) {
    if (ticket.getStatus() != TicketStatus.UNUSED || !PAID.equals(qualification.getOrderStatus())) {
      throw conflict("Only an unused ticket from a paid order can be used.");
    }
  }

  private static boolean isCanonicalCode(String code) {
    if (code == null || !code.matches("^[A-Za-z0-9_-]{43}$")) {
      return false;
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(code);
      return bytes.length == 32
          && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(code);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String newCode() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hash(String code) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
    }
  }

  private static TicketView view(TicketProjection projection) {
    return new TicketView(
        projection.getId(),
        projection.getOrderItemId(),
        projection.getOrderId(),
        projection.getEventId(),
        projection.getSessionId(),
        projection.getSkuId(),
        projection.getStatus(),
        projection.getUsedAt() == null ? null : instant(projection.getUsedAt()),
        instant(projection.getCreatedAt()));
  }

  private void validatePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request");
    }
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC);
  }

  private static ApiException invalidCode() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "INVALID_CHECKIN_CODE", "Check-in code has invalid format.");
  }

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
  }

  private static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT", message);
  }
}
