package com.eventflow.common.security;

import com.eventflow.common.error.ApiError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfiguration {
  private static final Set<String> KNOWN_ROLES = Set.of("USER", "ORGANIZER", "ADMIN");

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
    byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (secret.isBlank() || keyBytes.length < 32) {
      throw new IllegalStateException("app.jwt.secret must contain at least 32 UTF-8 bytes.");
    }
    return new SecretKeySpec(keyBytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  JwtDecoder jwtDecoder(
      SecretKey jwtSecretKey, @Value("${app.jwt.issuer:eventflow}") String issuer) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> claimsValidator = this::validateClaims;
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), claimsValidator));
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Object claim = jwt.getClaims().get("roles");
          if (!(claim instanceof List<?> roles)) {
            return List.of();
          }
          return roles.stream()
              .filter(String.class::isInstance)
              .map(String.class::cast)
              .filter(KNOWN_ROLES::contains)
              .distinct()
              .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
              .toList();
        });
    return converter;
  }

  @Bean
  AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
    return (request, response, exception) ->
        writeError(
            response,
            objectMapper,
            HttpStatus.UNAUTHORIZED,
            "UNAUTHORIZED",
            "Authentication is required or the access token is invalid.");
  }

  @Bean
  AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
    return (request, response, exception) ->
        writeError(
            response,
            objectMapper,
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "You do not have permission to access this resource.");
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      AuthenticationEntryPoint authenticationEntryPoint,
      AccessDeniedHandler accessDeniedHandler)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/**")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/orders",
                        "/api/v1/orders/**",
                        "/api/v1/me/tickets",
                        "/api/v1/me/tickets/**")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers("/api/v1/organizer/**")
                    .hasAnyRole("ORGANIZER", "ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
        .build();
  }

  private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
    if (!validSubject(jwt.getSubject()) || !validRoles(jwt.getClaims().get("roles"))) {
      return invalidClaims();
    }
    Instant expiresAt = jwt.getExpiresAt();
    if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
      return invalidClaims();
    }
    return OAuth2TokenValidatorResult.success();
  }

  private static boolean validSubject(String subject) {
    if (subject == null || !subject.matches("[1-9][0-9]*")) {
      return false;
    }
    try {
      return Long.parseLong(subject) > 0;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static boolean validRoles(Object claim) {
    if (!(claim instanceof List<?> roles) || roles.isEmpty()) {
      return false;
    }
    Set<String> found = new HashSet<>();
    for (Object value : roles) {
      if (!(value instanceof String role) || !KNOWN_ROLES.contains(role) || !found.add(role)) {
        return false;
      }
    }
    return true;
  }

  private static OAuth2TokenValidatorResult invalidClaims() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "Access token claims are invalid.", null));
  }

  private static void writeError(
      jakarta.servlet.http.HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
  }
}
