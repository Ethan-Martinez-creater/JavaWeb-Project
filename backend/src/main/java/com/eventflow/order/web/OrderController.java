package com.eventflow.order.web;

import com.eventflow.common.web.PageResponse;
import com.eventflow.order.application.OrderService;
import com.eventflow.order.web.OrderDtos.CreateOrder;
import com.eventflow.order.web.OrderDtos.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<OrderView> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrder input) {
    var result = service.create(userId(jwt), input.skuId(), input.idempotencyKey());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .location(URI.create("/api/v1/orders/" + result.order().id()))
        .body(result.order());
  }

  @GetMapping
  public PageResponse<OrderView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(userId(jwt), page, size);
  }

  @GetMapping("/{id}")
  public OrderView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
    return service.detail(id, userId(jwt));
  }

  @PostMapping("/{id}/pay")
  public OrderView pay(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
    return service.pay(id, userId(jwt));
  }

  @PostMapping("/{id}/cancel")
  public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
    return service.cancel(id, userId(jwt));
  }

  private static long userId(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }
}
