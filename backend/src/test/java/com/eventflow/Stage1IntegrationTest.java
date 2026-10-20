package com.eventflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eventflow.identity.application.AuthService;
import com.eventflow.identity.application.DevOrganizerInitializer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Black-box tests against a real MySQL 8.4 database and the application's real HTTP/security stack.
 * Docker is required: the suite deliberately has no disabled or assume-based fallback.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.orders.expiry-scan-enabled=false")
class Stage1IntegrationTest {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final String JWT_SECRET = randomHexSecret();
  private static final String ISSUER = "eventflow";
  private static final String PASSWORD = "StageOne-Test-Password-42";
  private static final AtomicBoolean FRESH_SCHEMA_CHECKED = new AtomicBoolean();

  @Container
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              "mysql@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364")
          .withDatabaseName("eventflow_test")
          // Testcontainers 1.x ships a legacy 10 MiB redo setting that stalls MySQL 8.4 init.
          .withCommand("mysqld", "--innodb-redo-log-capacity=128M")
          // This is still the real MySQL engine; only disposable test data files use tmpfs.
          .withTmpFs(Map.of("/var/lib/mysql", "rw,size=512m"))
          .withStartupTimeoutSeconds(300)
          .withUsername("eventflow_test")
          .withPassword("eventflow_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("app.jwt.secret", () -> JWT_SECRET);
    registry.add("app.jwt.issuer", () -> ISSUER);
    registry.add("app.jwt.ttl-seconds", () -> 900);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private AuthService authService;
  @LocalServerPort private int port;

  private Account organizer;
  private Account secondOrganizer;
  private Account user;
  private Account admin;
  private String organizerToken;
  private String secondOrganizerToken;
  private String userToken;
  private String adminToken;

  private void verifyFreshDatabaseHasFlywaySchemaAndNoDefaultAccounts() {
    assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
            Long.class));
    assertEquals(
        0L,
        jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class),
        "The default application profile must not seed accounts");
    assertEquals(
        5L,
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name IN ('users','user_roles','events','event_sessions','ticket_skus')
            """,
            Long.class));
    assertEquals(
        4L,
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL
              AND table_name IN ('user_roles','events','event_sessions','ticket_skus')
            """,
            Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ticket_skus'
              AND index_name = 'uq_skus_session_name' AND non_unique = 0
            """,
            Long.class));
  }

  @BeforeEach
  void prepareAccountsAndTokens() throws Exception {
    if (FRESH_SCHEMA_CHECKED.compareAndSet(false, true)) {
      verifyFreshDatabaseHasFlywaySchemaAndNoDefaultAccounts();
    }
    jdbc.execute("DELETE FROM ticket_skus");
    jdbc.execute("DELETE FROM event_sessions");
    jdbc.execute("DELETE FROM events");
    jdbc.execute("DELETE FROM user_roles");
    jdbc.execute("DELETE FROM users");

    String run = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    organizer = insertAccount("organizer-" + run + "@example.test", "ORGANIZER");
    secondOrganizer = insertAccount("second-" + run + "@example.test", "ORGANIZER");
    user = insertAccount("user-" + run + "@example.test", "USER");
    admin = insertAccount("admin-" + run + "@example.test", "ADMIN");
    organizerToken =
        jwt(organizer.id(), List.of("ORGANIZER"), Instant.now().plusSeconds(900), JWT_SECRET);
    secondOrganizerToken =
        jwt(secondOrganizer.id(), List.of("ORGANIZER"), Instant.now().plusSeconds(900), JWT_SECRET);
    userToken = jwt(user.id(), List.of("USER"), Instant.now().plusSeconds(900), JWT_SECRET);
    adminToken = jwt(admin.id(), List.of("ADMIN"), Instant.now().plusSeconds(900), JWT_SECRET);
  }

  @Test
  void registrationNormalizesEmailHashesPasswordAndRejectsInvalidOrDuplicateEmail()
      throws Exception {
    String email = "Stage1." + UUID.randomUUID().toString().substring(0, 8) + "@Example.Test";
    HttpResponse<String> created =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", email, "password", "Long-Valid-Password-42"));
    assertStatus(201, created);
    JsonNode response = json(created);
    long id = response.path("id").asLong();
    assertTrue(id > 0);
    assertEquals(email.toLowerCase(), response.path("email").asText());
    assertEquals(List.of("USER"), strings(response.path("roles")));

    String storedHash =
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
    assertNotNull(storedHash);
    assertFalse(storedHash.equals("Long-Valid-Password-42"));
    assertFalse(storedHash.isBlank());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role = 'USER'", Long.class, id));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role <> 'USER'",
            Long.class,
            id));
    assertStatus(
        200,
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            Map.of("email", email.toLowerCase(), "password", "Long-Valid-Password-42")));

    HttpResponse<String> duplicate =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", email.toLowerCase(), "password", "Another-Valid-Password-42"));
    HttpResponse<String> invalid =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", "not-an-email", "password", "Long-Valid-Password-42"));
    assertStatus(409, duplicate);
    assertStatus(400, invalid);
    assertApiError(duplicate);
    assertApiError(invalid);
  }

  @Test
  void registrationRejectsRoleUserIdAndOtherPrivilegeFields() throws Exception {
    String email = "escalation-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
    for (Map<String, Object> request :
        List.<Map<String, Object>>of(
            Map.of("email", email, "password", PASSWORD, "role", "ADMIN"),
            Map.of("email", email, "password", PASSWORD, "userId", 1),
            Map.of("email", email, "password", PASSWORD, "isAdmin", true))) {
      HttpResponse<String> response = call("POST", "/api/v1/auth/register", null, request);
      assertStatus(400, response);
      assertApiError(response);
    }
    assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
  }

  @Test
  void passwordLengthCountsUnicodeCharactersAndEnforcesBcryptUtf8ByteLimit() throws Exception {
    String base = "unicode-" + UUID.randomUUID().toString().substring(0, 8);
    HttpResponse<String> fourCharacters =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", base + "-short@example.test", "password", "汉字汉字"));
    String exactlyTenCharacters = "汉字汉字汉字汉字汉字";
    HttpResponse<String> tenCharacters =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", base + "-ten@example.test", "password", exactlyTenCharacters));
    String exactly72Bytes = "汉".repeat(24);
    HttpResponse<String> seventyTwoBytes =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", base + "-72@example.test", "password", exactly72Bytes));
    String over72Bytes = "汉".repeat(25);
    assertEquals(72, exactly72Bytes.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(75, over72Bytes.getBytes(StandardCharsets.UTF_8).length);
    HttpResponse<String> seventyFiveBytes =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            Map.of("email", base + "-75@example.test", "password", over72Bytes));

    assertStatus(400, fourCharacters);
    assertStatus(201, tenCharacters);
    assertStatus(201, seventyTwoBytes);
    assertStatus(400, seventyFiveBytes);
    assertApiError(fourCharacters);
    assertApiError(seventyFiveBytes);
    assertStatus(
        200,
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            Map.of("email", base + "-72@example.test", "password", exactly72Bytes)));
  }

  @Test
  void loginReturnsBearerTokenMeReturnsIdentityAndCredentialFailuresAreIndistinguishable()
      throws Exception {
    HttpResponse<String> login =
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            Map.of("email", user.email(), "password", PASSWORD));
    assertStatus(200, login);
    assertEquals("Bearer", json(login).path("tokenType").asText());
    assertTrue(json(login).path("expiresInSeconds").asLong() > 0);
    String token = json(login).path("accessToken").asText();
    assertFalse(token.isBlank());

    HttpResponse<String> me = call("GET", "/api/v1/auth/me", token, null);
    assertStatus(200, me);
    assertEquals(user.id(), json(me).path("id").asLong());
    assertEquals(user.email(), json(me).path("email").asText());
    assertEquals(List.of("USER"), strings(json(me).path("roles")));

    HttpResponse<String> wrongPassword =
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            Map.of("email", user.email(), "password", "Wrong-Password-42"));
    HttpResponse<String> unknown =
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            Map.of(
                "email", "missing-" + UUID.randomUUID() + "@example.test", "password", PASSWORD));
    assertStatus(401, wrongPassword);
    assertStatus(401, unknown);
    assertApiError(wrongPassword);
    assertApiError(unknown);
    assertEquals(json(wrongPassword).path("code").asText(), json(unknown).path("code").asText());
    assertEquals(
        json(wrongPassword).path("message").asText(), json(unknown).path("message").asText());
  }

  @Test
  void devOrganizerSeedIsAbsentByDefaultIdempotentAndRefusesExistingConflictingAccounts()
      throws Exception {
    assertEquals(0, applicationContext.getBeanNamesForType(DevOrganizerInitializer.class).length);

    String seedEmail = "dev-seed-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    authService.ensureDevOrganizer(seedEmail, PASSWORD);
    Long seedId =
        jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, seedEmail);
    String seedHash =
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, seedId);
    assertEquals(
        "ACTIVE",
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, seedId));
    assertEquals(Set.of("USER", "ORGANIZER"), new HashSet<>(roles(seedId)));
    authService.ensureDevOrganizer(seedEmail, PASSWORD);
    assertEquals(
        seedId, jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, seedEmail));
    assertEquals(
        seedHash,
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, seedId));

    assertThrows(
        IllegalStateException.class,
        () -> authService.ensureDevOrganizer(seedEmail, "Different-Valid-Password-21"));
    assertEquals(
        seedHash,
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, seedId));

    String publicEmail =
        "public-user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    Account publicUser = insertAccount(publicEmail, "USER");
    String publicHash =
        jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE id = ?", String.class, publicUser.id());
    assertThrows(
        IllegalStateException.class, () -> authService.ensureDevOrganizer(publicEmail, PASSWORD));
    assertEquals(
        publicHash,
        jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE id = ?", String.class, publicUser.id()));
    assertEquals(List.of("USER"), roles(publicUser.id()));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'",
            Long.class,
            publicUser.id()));

    jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", seedId);
    assertThrows(
        IllegalStateException.class, () -> authService.ensureDevOrganizer(seedEmail, PASSWORD));
    assertEquals(
        "DISABLED",
        jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, seedId));
    assertEquals(
        seedHash,
        jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, seedId));
    assertEquals(Set.of("USER", "ORGANIZER"), new HashSet<>(roles(seedId)));
  }

  @Test
  void expiredMalformedWrongSignatureAndMalformedClaimTokensAreRejected() throws Exception {
    List<String> invalidTokens =
        List.of(
            jwt(user.id(), List.of("USER"), Instant.now().minusSeconds(1), JWT_SECRET),
            jwt(user.id(), List.of("USER"), Instant.now().plusSeconds(900), "other-test-secret"),
            "not.a.jwt",
            jwt(user.id(), List.of("USER", "USER"), Instant.now().plusSeconds(900), JWT_SECRET),
            jwt(0, List.of("USER"), Instant.now().plusSeconds(900), JWT_SECRET));
    for (String token : invalidTokens) {
      HttpResponse<String> response = call("GET", "/api/v1/auth/me", token, null);
      assertStatus(401, response);
      assertApiError(response);
    }
    HttpResponse<String> anonymous = call("GET", "/api/v1/auth/me", null, null);
    HttpResponse<String> anonymousOrganizer = call("GET", "/api/v1/organizer/events", null, null);
    assertStatus(401, anonymous);
    assertStatus(401, anonymousOrganizer);
    assertApiError(anonymous);
    assertApiError(anonymousOrganizer);
  }

  @Test
  void userIsForbiddenFromOrganizerRoutesAndDraftsStayHiddenFromPublicReaders() throws Exception {
    HttpResponse<String> forbidden = call("GET", "/api/v1/organizer/events", userToken, null);
    assertStatus(403, forbidden);
    assertApiError(forbidden);

    long draft = createEvent(organizerToken, "Private draft");
    HttpResponse<String> publicList = call("GET", "/api/v1/events", null, null);
    HttpResponse<String> publicDetail = call("GET", "/api/v1/events/" + draft, null, null);
    assertStatus(200, publicList);
    assertStatus(404, publicDetail);
    assertApiError(publicDetail);
    assertEquals(0L, json(publicList).path("total").asLong());
  }

  @Test
  void onlyOwnerOrAdminCanManageEventsAndAdminListingIsStillOwnerScoped() throws Exception {
    long eventId = createEvent(organizerToken, "Organizer-owned");
    long sessionId =
        addSession(organizerToken, eventId, "Owner session", futureStart(), futureEnd());
    addSku(organizerToken, sessionId, "Owner ticket", 100, 5);
    HttpResponse<String> organizerList =
        call("GET", "/api/v1/organizer/events?page=0&size=20", organizerToken, null);
    assertStatus(200, organizerList);
    assertEquals(1, json(organizerList).path("items").size());
    assertEquals(eventId, json(organizerList).path("items").path(0).path("id").asLong());
    assertEquals(
        organizer.id(), json(organizerList).path("items").path(0).path("organizerId").asLong());
    List<HttpResponse<String>> denied =
        List.of(
            call(
                "PATCH",
                "/api/v1/organizer/events/" + eventId,
                secondOrganizerToken,
                Map.of("title", "Intrusion")),
            call(
                "POST",
                "/api/v1/organizer/events/" + eventId + "/sessions",
                secondOrganizerToken,
                sessionInput("Intrusion", futureStart(), futureEnd())),
            call(
                "POST",
                "/api/v1/organizer/sessions/" + sessionId + "/ticket-skus",
                secondOrganizerToken,
                skuInput("Intrusion", 1, 1)),
            call(
                "POST",
                "/api/v1/organizer/events/" + eventId + "/publish",
                secondOrganizerToken,
                null));
    for (HttpResponse<String> response : denied) {
      assertStatus(404, response);
      assertApiError(response);
    }

    HttpResponse<String> adminEdit =
        call(
            "PATCH",
            "/api/v1/organizer/events/" + eventId,
            adminToken,
            Map.of("title", "Admin edited"));
    assertStatus(200, adminEdit);
    long adminSession =
        addSession(adminToken, eventId, "Admin session", futureStart(), futureEnd());
    addSku(adminToken, adminSession, "Admin ticket", 200, 2);
    assertStatus(
        200, call("POST", "/api/v1/organizer/events/" + eventId + "/publish", adminToken, null));

    long adminEvent = createEvent(adminToken, "Admin-owned");
    HttpResponse<String> adminList =
        call("GET", "/api/v1/organizer/events?page=0&size=20", adminToken, null);
    assertStatus(200, adminList);
    assertEquals(1, json(adminList).path("items").size());
    assertEquals(adminEvent, json(adminList).path("items").path(0).path("id").asLong());
    assertEquals(admin.id(), json(adminList).path("items").path(0).path("organizerId").asLong());
  }

  @Test
  void organizerCreatesAndEditsDraftsButCannotOverrideOwnerOrStatus() throws Exception {
    HttpResponse<String> forged =
        call(
            "POST",
            "/api/v1/organizer/events",
            organizerToken,
            Map.of("title", "Forged", "description", "Test", "organizerId", secondOrganizer.id()));
    assertStatus(400, forged);
    assertApiError(forged);

    long id = createEvent(organizerToken, "Draft");
    HttpResponse<String> title =
        call("PATCH", "/api/v1/organizer/events/" + id, organizerToken, Map.of("title", "Renamed"));
    HttpResponse<String> description =
        call(
            "PATCH",
            "/api/v1/organizer/events/" + id,
            organizerToken,
            Map.of("description", "Updated description"));
    assertStatus(200, title);
    assertStatus(200, description);
    assertEquals("Renamed", json(title).path("title").asText());
    assertEquals("Updated description", json(description).path("description").asText());
    for (Map<String, Object> patch :
        List.of(Map.<String, Object>of(), Map.<String, Object>of("status", "PUBLISHED"))) {
      HttpResponse<String> rejected =
          call("PATCH", "/api/v1/organizer/events/" + id, organizerToken, patch);
      assertStatus(400, rejected);
      assertApiError(rejected);
    }
    assertEquals(
        "DRAFT", jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, id));
  }

  @Test
  void rejectsBadTimeOffsetNegativeSkuValuesAndDuplicateNamesUsingMysqlConstraints()
      throws Exception {
    long eventId = createEvent(organizerToken, "Validation");
    HttpResponse<String> reversed =
        call(
            "POST",
            "/api/v1/organizer/events/" + eventId + "/sessions",
            organizerToken,
            sessionInput("Reversed", futureEnd(), futureStart()));
    HttpResponse<String> noOffset =
        call(
            "POST",
            "/api/v1/organizer/events/" + eventId + "/sessions",
            organizerToken,
            Map.of(
                "title",
                "No offset",
                "startsAt",
                "2030-01-02T10:00:00",
                "endsAt",
                "2030-01-02T11:00:00",
                "location",
                "Hall"));
    assertStatus(400, reversed);
    assertStatus(400, noOffset);
    assertApiError(reversed);
    assertApiError(noOffset);

    long session = addSession(organizerToken, eventId, "Good session", futureStart(), futureEnd());
    HttpResponse<String> negativePrice =
        call(
            "POST",
            "/api/v1/organizer/sessions/" + session + "/ticket-skus",
            organizerToken,
            skuInput("Negative price", -1, 1));
    HttpResponse<String> negativeStock =
        call(
            "POST",
            "/api/v1/organizer/sessions/" + session + "/ticket-skus",
            organizerToken,
            skuInput("Negative stock", 0, -1));
    assertStatus(400, negativePrice);
    assertStatus(400, negativeStock);
    assertApiError(negativePrice);
    assertApiError(negativeStock);

    long sku = addSku(organizerToken, session, "Standard", 0, 4);
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id = ?", Integer.class, sku));
    long soldOutSku = addSku(organizerToken, session, "Sold out", 0, 0);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id = ?", Integer.class, soldOutSku));
    HttpResponse<String> duplicate =
        call(
            "POST",
            "/api/v1/organizer/sessions/" + session + "/ticket-skus",
            organizerToken,
            skuInput("Standard", 100, 4));
    assertStatus(409, duplicate);
    assertApiError(duplicate);
  }

  @Test
  void publishRequiresSessionsTicketsAndAtLeastOneFutureSessionThenReturnsNestedCatalog()
      throws Exception {
    long noSession = createEvent(organizerToken, "No session");
    HttpResponse<String> missingSession =
        call("POST", "/api/v1/organizer/events/" + noSession + "/publish", organizerToken, null);
    assertStatus(409, missingSession);

    long noTicket = createEvent(organizerToken, "No ticket");
    addSession(organizerToken, noTicket, "Session", futureStart(), futureEnd());
    HttpResponse<String> missingTicket =
        call("POST", "/api/v1/organizer/events/" + noTicket + "/publish", organizerToken, null);
    assertStatus(409, missingTicket);
    assertApiError(missingSession);
    assertApiError(missingTicket);

    long past = createEvent(organizerToken, "Past only");
    Instant pastStart = Instant.now().minus(Duration.ofDays(3));
    Instant pastEnd = Instant.now().minus(Duration.ofDays(2));
    long pastSession =
        addSession(organizerToken, past, "Past", pastStart.toString(), pastEnd.toString());
    addSku(organizerToken, pastSession, "Past ticket", 10, 1);
    HttpResponse<String> pastPublish =
        call("POST", "/api/v1/organizer/events/" + past + "/publish", organizerToken, null);
    assertStatus(409, pastPublish);
    assertApiError(pastPublish);

    long event = createEvent(organizerToken, "Public event");
    long first = addSession(organizerToken, event, "First", futureStart(), futureEnd());
    long second = addSession(organizerToken, event, "Second", futureStart(), futureEnd());
    long sku = addSku(organizerToken, first, "First ticket", 1250, 12);
    HttpResponse<String> oneSessionMissingSku =
        call("POST", "/api/v1/organizer/events/" + event + "/publish", organizerToken, null);
    assertStatus(409, oneSessionMissingSku);
    assertApiError(oneSessionMissingSku);
    addSku(organizerToken, second, "Second ticket", 0, 2);

    HttpResponse<String> published =
        call("POST", "/api/v1/organizer/events/" + event + "/publish", organizerToken, null);
    assertStatus(200, published);
    assertEquals("PUBLISHED", json(published).path("status").asText());
    HttpResponse<String> detail = call("GET", "/api/v1/events/" + event, null, null);
    assertStatus(200, detail);
    JsonNode firstSku = json(detail).path("sessions").path(0).path("ticketSkus").path(0);
    assertEquals(sku, firstSku.path("id").asLong());
    assertEquals(1250, firstSku.path("priceCents").asLong());
    assertEquals(12, firstSku.path("availableStock").asInt());
  }

  @Test
  void everyWriteToPublishedEventIsRejectedAndConcurrentPublishOnlySucceedsOnce() throws Exception {
    long event = createEvent(organizerToken, "Published once");
    long session = addSession(organizerToken, event, "Session", futureStart(), futureEnd());
    addSku(organizerToken, session, "Ticket", 0, 1);

    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try {
      List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  ready.countDown();
                  try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return call(
                        "POST",
                        "/api/v1/organizer/events/" + event + "/publish",
                        organizerToken,
                        null);
                  } catch (Exception exception) {
                    throw new CompletionException(exception);
                  }
                },
                pool));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<HttpResponse<String>> results = futures.stream().map(CompletableFuture::join).toList();
      assertEquals(1L, results.stream().filter(r -> r.statusCode() == 200).count());
      assertEquals(1L, results.stream().filter(r -> r.statusCode() == 409).count());
      assertApiError(results.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow());
    } finally {
      start.countDown();
      pool.shutdownNow();
    }

    assertEquals(
        "PUBLISHED",
        jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, event));
    List<HttpResponse<String>> writes =
        List.of(
            call(
                "PATCH",
                "/api/v1/organizer/events/" + event,
                organizerToken,
                Map.of("title", "Changed")),
            call(
                "POST",
                "/api/v1/organizer/events/" + event + "/sessions",
                organizerToken,
                sessionInput("Late session", futureStart(), futureEnd())),
            call(
                "POST",
                "/api/v1/organizer/sessions/" + session + "/ticket-skus",
                organizerToken,
                skuInput("Late ticket", 1, 1)),
            call("POST", "/api/v1/organizer/events/" + event + "/publish", organizerToken, null));
    for (HttpResponse<String> response : writes) {
      assertStatus(409, response);
      assertApiError(response);
    }
    assertEquals(
        "Published once",
        jdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, event));
  }

  @Test
  void publicSearchUsesDatabasePaginationAndCountAndNeverIncludesDrafts() throws Exception {
    for (int i = 1; i <= 3; i++) {
      publishEvent(organizerToken, "Stage1 Meetup " + i);
    }
    publishEvent(organizerToken, "Unrelated concert");
    createEvent(organizerToken, "Stage1 Meetup private draft");
    String keyword = URLEncoder.encode("Stage1 Meetup", StandardCharsets.UTF_8);

    HttpResponse<String> page0 =
        call("GET", "/api/v1/events?keyword=" + keyword + "&page=0&size=2", null, null);
    HttpResponse<String> page1 =
        call("GET", "/api/v1/events?keyword=" + keyword + "&page=1&size=2", null, null);
    HttpResponse<String> page2 =
        call("GET", "/api/v1/events?keyword=" + keyword + "&page=2&size=2", null, null);
    assertStatus(200, page0);
    assertStatus(200, page1);
    assertStatus(200, page2);
    assertEquals(0, json(page0).path("page").asInt());
    assertEquals(2, json(page0).path("size").asInt());
    assertEquals(1, json(page1).path("page").asInt());
    assertEquals(2, json(page1).path("size").asInt());
    assertEquals(3, json(page0).path("total").asLong());
    assertEquals(2, json(page0).path("items").size());
    assertEquals(1, json(page1).path("items").size());
    assertEquals(3, json(page1).path("total").asLong());
    assertEquals(0, json(page2).path("items").size());
    for (JsonNode item : json(page0).path("items")) {
      assertEquals("PUBLISHED", item.path("status").asText());
    }

    HttpResponse<String> injection =
        call(
            "GET",
            "/api/v1/events?keyword="
                + URLEncoder.encode("' OR 1=1 --", StandardCharsets.UTF_8)
                + "&page=0&size=20",
            null,
            null);
    assertStatus(200, injection);
    assertEquals(0, json(injection).path("total").asLong());
    for (String invalidPath :
        List.of(
            "/api/v1/events?page=-1",
            "/api/v1/events?size=101",
            "/api/v1/organizer/events?size=101")) {
      HttpResponse<String> invalid =
          call("GET", invalidPath, invalidPath.contains("organizer") ? organizerToken : null, null);
      assertStatus(400, invalid);
      assertApiError(invalid);
    }
  }

  private Account insertAccount(String email, String role) throws Exception {
    HttpResponse<String> created =
        call("POST", "/api/v1/auth/register", null, Map.of("email", email, "password", PASSWORD));
    assertStatus(201, created);
    long id = json(created).path("id").asLong();
    if (!role.equals("USER")) {
      jdbc.update("UPDATE user_roles SET role = ? WHERE user_id = ? AND role = 'USER'", role, id);
    }
    return new Account(id, email);
  }

  private List<String> roles(long userId) {
    return jdbc.query(
        "SELECT role FROM user_roles WHERE user_id = ? ORDER BY role",
        (resultSet, rowNumber) -> resultSet.getString(1),
        userId);
  }

  private long createEvent(String token, String title) throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/events",
            token,
            Map.of("title", title, "description", "Stage 1 integration test"));
    assertStatus(201, response);
    assertEquals("DRAFT", json(response).path("status").asText());
    return json(response).path("id").asLong();
  }

  private long addSession(String token, long event, String title, String start, String end)
      throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/events/" + event + "/sessions",
            token,
            sessionInput(title, start, end));
    assertStatus(201, response);
    return json(response).path("id").asLong();
  }

  private long addSku(String token, long session, String name, long price, int stock)
      throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/sessions/" + session + "/ticket-skus",
            token,
            skuInput(name, price, stock));
    assertStatus(201, response);
    assertEquals(stock, json(response).path("availableStock").asInt());
    return json(response).path("id").asLong();
  }

  private long publishEvent(String token, String title) throws Exception {
    long event = createEvent(token, title);
    long session = addSession(token, event, "Session", futureStart(), futureEnd());
    addSku(token, session, "General", 100, 10);
    assertStatus(200, call("POST", "/api/v1/organizer/events/" + event + "/publish", token, null));
    return event;
  }

  private HttpResponse<String> call(String method, String path, String token, Object body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json");
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(
          method,
          HttpRequest.BodyPublishers.ofString(
              JSON.writeValueAsString(body), StandardCharsets.UTF_8));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> sessionInput(String title, String start, String end) {
    return Map.of("title", title, "startsAt", start, "endsAt", end, "location", "Main Hall");
  }

  private static Map<String, Object> skuInput(String name, long price, int stock) {
    return Map.of("name", name, "priceCents", price, "totalStock", stock);
  }

  private String futureStart() {
    return Instant.now().plus(Duration.ofDays(2)).toString();
  }

  private String futureEnd() {
    return Instant.now().plus(Duration.ofDays(2)).plus(Duration.ofHours(2)).toString();
  }

  private static JsonNode json(HttpResponse<String> response) {
    try {
      return JSON.readTree(response.body());
    } catch (Exception exception) {
      String contentType = response.headers().firstValue("content-type").orElse("<missing>");
      throw new AssertionError(
          "Expected JSON for HTTP " + response.statusCode() + " with content-type " + contentType,
          exception);
    }
  }

  private static List<String> strings(JsonNode array) {
    List<String> result = new ArrayList<>();
    array.forEach(item -> result.add(item.asText()));
    return result;
  }

  private static void assertStatus(int expected, HttpResponse<String> response) {
    assertEquals(
        expected,
        response.statusCode(),
        () ->
            "Expected HTTP "
                + expected
                + ", received "
                + response.statusCode()
                + " content-type="
                + response.headers().firstValue("content-type").orElse("<missing>"));
  }

  private static void assertApiError(HttpResponse<String> response) {
    JsonNode error = json(response);
    assertTrue(error.isObject(), "Errors must be JSON objects");
    for (String field : List.of("code", "message", "requestId", "timestamp")) {
      assertTrue(error.hasNonNull(field), "Error must include " + field);
      assertFalse(error.path(field).asText().isBlank(), field + " must not be blank");
    }
  }

  private static String jwt(long subject, List<String> roles, Instant expiresAt, String secret)
      throws Exception {
    long now = Instant.now().getEpochSecond();
    String header = base64Url(JSON.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
    String claims =
        base64Url(
            JSON.writeValueAsBytes(
                Map.of(
                    "iss",
                    ISSUER,
                    "sub",
                    Long.toString(subject),
                    "iat",
                    now,
                    "exp",
                    expiresAt.getEpochSecond(),
                    "roles",
                    roles)));
    String content = header + "." + claims;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content + "." + base64Url(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String randomHexSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private record Account(long id, String email) {}
}
