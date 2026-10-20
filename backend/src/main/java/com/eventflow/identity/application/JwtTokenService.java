package com.eventflow.identity.application;

import com.eventflow.identity.domain.UserEntity;
import com.eventflow.identity.web.dto.AccessTokenResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
  private final JwtEncoder jwtEncoder;
  private final String issuer;
  private final long ttlSeconds;

  public JwtTokenService(
      JwtEncoder jwtEncoder,
      @Value("${app.jwt.issuer:eventflow}") String issuer,
      @Value("${app.jwt.ttl-seconds:3600}") long ttlSeconds) {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("app.jwt.issuer must not be blank.");
    }
    if (ttlSeconds <= 0) {
      throw new IllegalArgumentException("app.jwt.ttl-seconds must be positive.");
    }
    this.jwtEncoder = jwtEncoder;
    this.issuer = issuer;
    this.ttlSeconds = ttlSeconds;
  }

  public AccessTokenResponse issue(UserEntity user, List<String> roles) {
    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(Long.toString(user.getId()))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("email", user.getEmail())
            .claim("roles", roles)
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessTokenResponse(token, "Bearer", ttlSeconds);
  }
}
