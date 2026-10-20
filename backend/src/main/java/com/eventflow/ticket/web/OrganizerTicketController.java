package com.eventflow.ticket.web;

import com.eventflow.ticket.application.TicketService;
import com.eventflow.ticket.web.TicketDtos.CheckInRequest;
import com.eventflow.ticket.web.TicketDtos.CheckInResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizer/tickets")
public class OrganizerTicketController {
  private final TicketService ticketService;

  public OrganizerTicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @PostMapping("/check-in")
  public CheckInResponse checkIn(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckInRequest request) {
    boolean admin = jwt.getClaimAsStringList("roles").contains("ADMIN");
    return ticketService.checkIn(userId(jwt), admin, request.checkinCode());
  }

  private static long userId(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }
}
