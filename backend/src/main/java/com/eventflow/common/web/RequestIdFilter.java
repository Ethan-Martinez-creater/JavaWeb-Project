package com.eventflow.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // Generate our own id rather than trusting an unbounded/untrusted log field from clients.
    String requestId = UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", requestId);
    try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
      chain.doFilter(request, response);
    }
  }
}
