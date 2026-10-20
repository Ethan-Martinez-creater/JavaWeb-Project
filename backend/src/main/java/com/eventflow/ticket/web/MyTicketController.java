package com.eventflow.ticket.web;

import com.eventflow.common.web.PageResponse;
import com.eventflow.ticket.application.TicketService;
import com.eventflow.ticket.web.TicketDtos.CheckinCodeResponse;
import com.eventflow.ticket.web.TicketDtos.TicketView;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/tickets")
public class MyTicketController {
  private final TicketService ticketService;

  public MyTicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @GetMapping
  public PageResponse<TicketView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ticketService.listMine(userId(jwt), page, size);
  }

  @PostMapping("/{ticketId}/checkin-code")
  public CheckinCodeResponse rotateCode(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long ticketId) {
    return ticketService.rotateCheckinCode(ticketId, userId(jwt));
  }

  private static long userId(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }
}
