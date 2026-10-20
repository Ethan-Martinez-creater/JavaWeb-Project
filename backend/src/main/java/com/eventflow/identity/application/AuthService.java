package com.eventflow.identity.application;

import com.eventflow.common.error.ApiException;
import com.eventflow.identity.domain.UserEntity;
import com.eventflow.identity.infrastructure.mapper.UserMapper;
import com.eventflow.identity.web.dto.AccessTokenResponse;
import com.eventflow.identity.web.dto.UserResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final String USER_ROLE = "USER";
  private static final String ORGANIZER_ROLE = "ORGANIZER";
  private static final String ACTIVE_STATUS = "ACTIVE";
  private static final Set<String> DEV_ORGANIZER_ROLES = Set.of(USER_ROLE, ORGANIZER_ROLE);

  private final UserMapper userMapper;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;

  public AuthService(
      UserMapper userMapper, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService) {
    this.userMapper = userMapper;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
  }

  @Transactional
  public UserResponse register(String email, String password) {
    String normalizedEmail = normalizeEmail(email);
    if (!hasValidPasswordLength(password)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PASSWORD",
          "Password must contain at least 10 characters and at most 72 UTF-8 bytes.");
    }

    if (userMapper.findByEmail(normalizedEmail) != null) {
      throw emailConflict();
    }

    UserEntity user = new UserEntity();
    user.setEmail(normalizedEmail);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setStatus(ACTIVE_STATUS);

    try {
      userMapper.insertUser(user);
    } catch (DuplicateKeyException exception) {
      // The unique index is the final guard when two registrations race.
      throw emailConflict();
    }
    userMapper.insertRole(user.getId(), USER_ROLE);
    return new UserResponse(user.getId(), normalizedEmail, List.of(USER_ROLE));
  }

  @Transactional(readOnly = true)
  public AccessTokenResponse login(String email, String password) {
    if (!hasValidPasswordLength(password)) {
      throw invalidCredentials();
    }
    UserEntity user = userMapper.findByEmail(normalizeEmail(email));
    if (user == null
        || !ACTIVE_STATUS.equals(user.getStatus())
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw invalidCredentials();
    }

    List<String> roles = List.copyOf(userMapper.findRolesByUserId(user.getId()));
    if (roles.isEmpty()) {
      throw invalidCredentials();
    }
    return jwtTokenService.issue(user, roles);
  }

  @Transactional
  public void ensureDevOrganizer(String email, String password) {
    String normalizedEmail = normalizeEmail(email);
    if (!hasValidPasswordLength(password)) {
      throw new IllegalStateException(
          "app.dev.organizer-password must contain at least 10 characters and at most 72 UTF-8 bytes.");
    }

    UserEntity existing = userMapper.findByEmail(normalizedEmail);
    if (existing != null) {
      List<String> roles = userMapper.findRolesByUserId(existing.getId());
      boolean isSameSeed =
          ACTIVE_STATUS.equals(existing.getStatus())
              && passwordEncoder.matches(password, existing.getPasswordHash())
              && roles.size() == DEV_ORGANIZER_ROLES.size()
              && new HashSet<>(roles).equals(DEV_ORGANIZER_ROLES);
      if (isSameSeed) {
        return;
      }
      throw new IllegalStateException(
          "Refusing dev organizer initialization because the configured email already belongs to another account.");
    }

    UserEntity user = new UserEntity();
    user.setEmail(normalizedEmail);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setStatus(ACTIVE_STATUS);
    try {
      userMapper.insertUser(user);
    } catch (DuplicateKeyException exception) {
      throw new IllegalStateException(
          "Refusing dev organizer initialization because the configured email already exists.");
    }
    userMapper.insertRole(user.getId(), USER_ROLE);
    userMapper.insertRole(user.getId(), ORGANIZER_ROLE);
  }

  private static String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean hasValidPasswordLength(String password) {
    if (password == null) {
      return false;
    }
    int bytes = password.getBytes(StandardCharsets.UTF_8).length;
    int characters = password.codePointCount(0, password.length());
    return characters >= 10 && bytes <= 72;
  }

  private static ApiException invalidCredentials() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
  }

  private static ApiException emailConflict() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "EMAIL_ALREADY_REGISTERED",
        "An account with this email already exists.");
  }
}
