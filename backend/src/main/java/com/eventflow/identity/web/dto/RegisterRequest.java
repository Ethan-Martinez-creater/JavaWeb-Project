package com.eventflow.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email @Size(max = 254) String email, @NotNull String password) {
  public RegisterRequest {
    email = email == null ? null : email.trim();
  }
}
