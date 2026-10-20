package com.eventflow.identity.web;

import com.eventflow.identity.application.AuthService;
import com.eventflow.identity.web.dto.AccessTokenResponse;
import com.eventflow.identity.web.dto.LoginRequest;
import com.eventflow.identity.web.dto.RegisterRequest;
import com.eventflow.identity.web.dto.UserResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
    UserResponse response = authService.register(request.email(), request.password());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/login")
  public AccessTokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request.email(), request.password());
  }

  @GetMapping("/me")
  public UserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
    return new UserResponse(
        Long.parseLong(jwt.getSubject()),
        jwt.getClaimAsString("email"),
        List.copyOf(jwt.getClaimAsStringList("roles")));
  }
}
