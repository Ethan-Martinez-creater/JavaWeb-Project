package com.eventflow.identity.application;

import com.eventflow.identity.web.dto.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
public class DevOrganizerInitializer {
  @Bean
  ApplicationRunner initializeDevOrganizer(
      AuthService authService,
      Validator validator,
      @Value("${app.dev.organizer-email:}") String email,
      @Value("${app.dev.organizer-password:}") String password) {
    return arguments -> {
      boolean hasEmail = email != null && !email.isBlank();
      boolean hasPassword = password != null && !password.isBlank();
      if (!hasEmail && !hasPassword) {
        return;
      }
      if (hasEmail != hasPassword) {
        throw new IllegalStateException(
            "Set both app.dev.organizer-email and app.dev.organizer-password, or leave both empty.");
      }

      RegisterRequest seed = new RegisterRequest(email, password);
      Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(seed);
      if (!violations.isEmpty()) {
        throw new IllegalStateException(
            "Dev organizer seed configuration is invalid; check the email and password requirements.");
      }
      authService.ensureDevOrganizer(seed.email(), password);
    };
  }
}
