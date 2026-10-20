package com.eventflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eventflow.order.application.OrderExpiryScanner;
import com.eventflow.order.application.OrderService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Stage 2 contract tests against pinned MySQL 8.4 and the real HTTP/security stack. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.orders.expiry-scan-enabled=false",
      "app.events.cache.enabled=false",
      "app.rate-limit.orders.enabled=false",
      "app.messaging.enabled=false",
      "management.health.redis.enabled=false",
      "management.health.rabbit.enabled=false"
    })
@Import(Stage2IntegrationTest.ClockConfiguration.class)
class Stage2IntegrationTest {
  private static final String MYSQL_IMAGE =
      "mysql@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364";
  private static final String ISSUER = "eventflow";
  private static final String JWT_SECRET = randomHexSecret();
  private static final String PASSWORD_HASH = "unused-fixture-password-hash";
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private static final AtomicInteger FRESH_SCHEMA_CHECKED = new AtomicInteger();
  private static final int CONCURRENCY_WORKERS = 48;

  @Container private static final MySQLContainer<?> MYSQL = mysqlContainer("eventflow_stage2_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("app.jwt.secret", () -> JWT_SECRET);
    registry.add("app.jwt.issuer", () -> ISSUER);
    registry.add("app.jwt.ttl-seconds", () -> 3600);
    registry.add("app.orders.expiry-scan-enabled", () -> false);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableTestClock clock;
  @Autowired private OrderService orders;
  @Autowired private OrderExpiryScanner expiryScanner;
  @Autowired private PlatformTransactionManager transactionManager;
  @LocalServerPort private int port;

  private Account user;
  private Account secondUser;
  private Account organizer;
  private Account secondOrganizer;
  private Account admin;

  @BeforeEach
  void resetDatabaseAndPreparePrincipals() throws Exception {
    clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    if (FRESH_SCHEMA_CHECKED.compareAndSet(0, 1)) {
      verifyFreshV1ToV2Schema();
    }
    jdbc.execute("DELETE FROM user_notifications");
    jdbc.execute("DELETE FROM consumed_events");
    jdbc.execute("DELETE FROM outbox_events");
    jdbc.execute("DELETE FROM checkin_records");
    jdbc.execute("DELETE FROM tickets");
    jdbc.execute("DELETE FROM order_items");
    jdbc.execute("DELETE FROM orders");
    jdbc.execute("DELETE FROM ticket_skus");
    jdbc.execute("DELETE FROM event_sessions");
    jdbc.execute("DELETE FROM events");
    jdbc.execute("DELETE FROM user_roles");
    jdbc.execute("DELETE FROM users");

    user = insertAccount("USER");
    secondUser = insertAccount("USER");
    organizer = insertAccount("ORGANIZER");
    secondOrganizer = insertAccount("ORGANIZER");
    admin = insertAccount("ADMIN");
  }

  @Test
  void freshDatabaseRunsFlywayV1ThenV2AndCreatesRequiredTablesKeysAndChecks() {
    assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1",
            Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1",
            Long.class));
    assertEquals(
        9L,
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema=DATABASE() AND table_name IN
              ('users','user_roles','events','event_sessions','ticket_skus',
               'orders','order_items','tickets','checkin_records')
            """,
            Long.class));
    assertEquals(
        0L,
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.key_column_usage
            WHERE table_schema=DATABASE() AND table_name='orders'
              AND column_name='requested_sku_id' AND referenced_table_name IS NOT NULL
            """,
            Long.class),
        "requested_sku_id records request semantics and intentionally has no SKU FK");
  }

  @Test
  void migrationFromAnExistingV1DatabasePreservesCatalogRowsAndAppliesV2() throws Exception {
    try (MySQLContainer<?> upgradeDb = mysqlContainer("eventflow_v1_upgrade_test")) {
      upgradeDb.start();
      Flyway v1 =
          Flyway.configure()
              .dataSource(upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())
              .locations("classpath:db/migration")
              .target(MigrationVersion.fromVersion("1"))
              .load();
      v1.migrate();

      long userId;
      long eventId;
      try (Connection connection =
          DriverManager.getConnection(
              upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO users(email,password_hash,status) VALUES (?,?, 'ACTIVE')",
                Statement.RETURN_GENERATED_KEYS)) {
          statement.setString(1, "v1-upgrade-fixture@example.test");
          statement.setString(2, PASSWORD_HASH);
          statement.executeUpdate();
          try (ResultSet keys = statement.getGeneratedKeys()) {
            assertTrue(keys.next());
            userId = keys.getLong(1);
          }
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO user_roles(user_id,role) VALUES (?, 'ORGANIZER')")) {
          statement.setLong(1, userId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO events(organizer_id,title,description,status) VALUES (?,?,'legacy','PUBLISHED')",
                Statement.RETURN_GENERATED_KEYS)) {
          statement.setLong(1, userId);
          statement.setString(2, "Existing V1 event");
          statement.executeUpdate();
          try (ResultSet keys = statement.getGeneratedKeys()) {
            assertTrue(keys.next());
            eventId = keys.getLong(1);
          }
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO event_sessions(event_id,title,starts_at,ends_at,location) VALUES (?,?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'hall')")) {
          statement.setLong(1, eventId);
          statement.setString(2, "Existing V1 session");
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO ticket_skus(session_id,name,price_cents,total_stock,available_stock) VALUES (1,'legacy sku',100,5,5)")) {
          statement.executeUpdate();
        }
      }

      Flyway.configure()
          .dataSource(upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())
          .locations("classpath:db/migration")
          .load()
          .migrate();
      try (Connection connection =
              DriverManager.getConnection(
                  upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword());
          Statement statement = connection.createStatement()) {
        try (ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM events WHERE id="
                    + eventId
                    + " AND title='Existing V1 event'")) {
          assertTrue(result.next());
          assertEquals(1L, result.getLong(1));
        }
        try (ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM users WHERE id="
                    + userId
                    + " AND email='v1-upgrade-fixture@example.test'")) {
          assertTrue(result.next());
          assertEquals(1L, result.getLong(1));
        }
        try (ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1")) {
          assertTrue(result.next());
          assertEquals(1L, result.getLong(1));
        }
        try (ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='orders'")) {
          assertTrue(result.next());
          assertEquals(1L, result.getLong(1));
        }
      }
    }
  }

  @Test
  void stageTwoSqlIndexesForeignKeysAndChecksAreEnforcedByMysql() throws Exception {
    assertEquals(1L, countIndex("orders", "uq_orders_user_idempotency", true));
    assertEquals(1L, countIndex("orders", "uq_orders_order_no", true));
    assertEquals(1L, countIndex("orders", "idx_orders_user_id", false));
    assertEquals(1L, countIndex("orders", "idx_orders_expiry", false));
    assertEquals(1L, countIndex("order_items", "uq_order_items_order", true));
    assertEquals(1L, countIndex("tickets", "uq_tickets_order_item", true));
    assertEquals(1L, countIndex("tickets", "uq_tickets_code_hash", true));
    assertEquals(1L, countIndex("tickets", "idx_tickets_owner_id", false));
    assertEquals(1L, countIndex("checkin_records", "uq_checkin_records_ticket", true));
    assertTrue(
        jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE table_schema=DATABASE() AND table_name IN ('orders','order_items','tickets','checkin_records')
                  AND referenced_table_name IS NOT NULL
                """,
                Long.class)
            >= 7L);
    for (String check :
        List.of(
            "ck_orders_request",
            "ck_orders_amount",
            "ck_orders_state",
            "ck_order_items_price",
            "ck_order_items_quantity",
            "ck_tickets_state",
            "ck_checkin_records_result")) {
      assertEquals(1L, countConstraint(check), "Missing enforced SQL check " + check);
    }

    Fixture fixture = publishedFixture("SQL constraints", 10, 500);
    HttpResponse<String> response = createOrder(user, fixture.skuId(), "constraint-valid-1");
    assertStatus(201, response);
    long orderId = json(response).path("id").asLong();
    long itemId = json(response).path("item").path("id").asLong();

    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE ticket_skus SET available_stock=total_stock+1 WHERE id=?",
                fixture.skuId()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE order_items SET quantity=2 WHERE id=?", itemId));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE orders SET total_price_cents=-1 WHERE id=?", orderId));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", orderId));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO order_items(order_id,sku_id,price_cents,quantity) VALUES (?,?,100,1)",
                999999999L,
                fixture.skuId()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO tickets(order_item_id,owner_user_id,status) VALUES (?,?, 'USED')",
                itemId,
                user.id()));

    HttpResponse<String> paid =
        call("POST", "/api/v1/orders/" + orderId + "/pay", user.token(), null);
    assertStatus(200, paid);
    long ticketId = json(paid).path("ticket").path("id").asLong();
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO checkin_records(ticket_id,operator_user_id,result) VALUES (?,?, 'FAILED')",
                ticketId,
                organizer.id()));
  }

  @Test
  void orderCreationValidatesSecurityOwnershipInputAvailabilityAndStableSnapshots()
      throws Exception {
    Fixture fixture = publishedFixture("Create and snapshot", 10, 1250);
    HttpResponse<String> anonymous =
        call("POST", "/api/v1/orders", null, orderRequest(fixture.skuId(), "anonymous-key-1"));
    HttpResponse<String> organizerDenied =
        call(
            "POST",
            "/api/v1/orders",
            organizer.token(),
            orderRequest(fixture.skuId(), "organizer-key-1"));
    HttpResponse<String> adminOrder =
        call(
            "POST",
            "/api/v1/orders",
            admin.token(),
            orderRequest(fixture.skuId(), "admin-key-0001"));
    assertStatus(401, anonymous);
    assertApiError(anonymous);
    assertStatus(403, organizerDenied);
    assertApiError(organizerDenied);
    assertStatus(201, adminOrder);
    assertEquals("PENDING_PAYMENT", json(adminOrder).path("status").asText());

    HttpResponse<String> zeroSku =
        call("POST", "/api/v1/orders", user.token(), orderRequest(0, "invalid-zero-sku-1"));
    HttpResponse<String> invalidKey =
        call("POST", "/api/v1/orders", user.token(), orderRequest(fixture.skuId(), "bad key"));
    HttpResponse<String> unknownField =
        call(
            "POST",
            "/api/v1/orders",
            user.token(),
            Map.of(
                "skuId",
                fixture.skuId(),
                "idempotencyKey",
                "unknown-field-1",
                "userId",
                admin.id()));
    HttpResponse<String> unknownSku =
        call("POST", "/api/v1/orders", user.token(), orderRequest(99999999, "missing-sku-001"));
    assertStatus(400, zeroSku);
    assertStatus(400, invalidKey);
    assertStatus(400, unknownField);
    assertStatus(409, unknownSku);
    assertApiError(zeroSku);
    assertApiError(invalidKey);
    assertApiError(unknownField);
    assertApiError(unknownSku);

    HttpResponse<String> created = createOrder(user, fixture.skuId(), "snapshot-key-0001");
    assertStatus(201, created);
    JsonNode body = json(created);
    assertEquals(1250, body.path("totalPriceCents").asLong());
    assertEquals(1250, body.path("item").path("priceCents").asLong());
    assertEquals(1, body.path("item").path("quantity").asInt());
    assertTrue(body.path("orderNo").asText().matches("[0-9a-f]{32}"));
    assertFalse(body.hasNonNull("ticket"));
    jdbc.update("UPDATE ticket_skus SET price_cents=9000 WHERE id=?", fixture.skuId());
    HttpResponse<String> detail =
        call("GET", "/api/v1/orders/" + body.path("id").asLong(), user.token(), null);
    assertStatus(200, detail);
    assertEquals(1250, json(detail).path("totalPriceCents").asLong());
    assertEquals(1250, json(detail).path("item").path("priceCents").asLong());
  }

  @Test
  void idempotencyReplaysExactlyOnceIsCaseSensitiveAndScopesKeysToEachUser() throws Exception {
    Fixture fixture = publishedFixture("Idempotency", 6, 1000);
    HttpResponse<String> first = createOrder(user, fixture.skuId(), "Replay-Key-0001");
    HttpResponse<String> replay = createOrder(user, fixture.skuId(), "Replay-Key-0001");
    HttpResponse<String> caseVariant = createOrder(user, fixture.skuId(), "replay-key-0001");
    HttpResponse<String> otherUser = createOrder(secondUser, fixture.skuId(), "Replay-Key-0001");
    assertStatus(201, first);
    assertStatus(200, replay);
    assertStatus(201, caseVariant);
    assertStatus(201, otherUser);
    assertEquals(json(first).path("id").asLong(), json(replay).path("id").asLong());
    assertNotEquals(json(first).path("id").asLong(), json(caseVariant).path("id").asLong());
    assertNotEquals(json(first).path("id").asLong(), json(otherUser).path("id").asLong());
    assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class));
  }

  @Test
  void parallelSameUserReplayAndParameterConflictAreSerializedByMysqlUniqueKey() throws Exception {
    TwoSkuFixture fixture = twoSkuPublishedFixture("Parallel idem", 8);
    int attempts = 24;
    List<HttpResponse<String>> sameParameter =
        parallelCalls(
            attempts,
            16,
            ignored -> createOrder(user, fixture.firstSkuId(), "same-key-parallel-001"));
    assertEquals(
        1L, sameParameter.stream().filter(response -> response.statusCode() == 201).count());
    assertEquals(
        23L, sameParameter.stream().filter(response -> response.statusCode() == 200).count());
    Set<Long> ids = new HashSet<>();
    sameParameter.forEach(response -> ids.add(json(response).path("id").asLong()));
    assertEquals(1, ids.size());
    assertEquals(
        7,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            fixture.firstSkuId()));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class));

    Account conflictUser = insertAccount("USER");
    List<HttpResponse<String>> conflictingParameters =
        parallelCalls(
            2,
            2,
            index ->
                createOrder(
                    conflictUser,
                    index == 0 ? fixture.firstSkuId() : fixture.secondSkuId(),
                    "parallel-param-0001"));
    assertEquals(1L, conflictingParameters.stream().filter(r -> r.statusCode() == 201).count());
    assertEquals(1L, conflictingParameters.stream().filter(r -> r.statusCode() == 409).count());
    conflictingParameters.stream()
        .filter(r -> r.statusCode() == 409)
        .forEach(
            response -> {
              assertApiError(response);
              assertEquals("IDEMPOTENCY_CONFLICT", errorCode(response));
            });
  }

  @Test
  void transactionRollbackAllowsRetryWithSameIdempotencyKey() throws Exception {
    Fixture fixture = publishedFixture("Rollback retry", 3, 775);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    assertThrows(
        RollbackMarker.class,
        () ->
            transaction.execute(
                status -> {
                  orders.create(user.id(), fixture.skuId(), "rollback-key-0001");
                  throw new RollbackMarker();
                }));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));

    HttpResponse<String> retry = createOrder(user, fixture.skuId(), "rollback-key-0001");
    assertStatus(201, retry);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
  }

  @Test
  void unavailableDraftStartedAndSoldOutSkusNeverCreateOrdersOrConsumeStock() throws Exception {
    Fixture draft = draftFixture("Draft SKU", 4, 100);
    HttpResponse<String> draftOrder = createOrder(user, draft.skuId(), "draft-order-key-1");
    assertStatus(409, draftOrder);
    assertApiError(draftOrder);

    Instant now = clock.instant();
    Fixture mixed = publishedEventWithStartedAndFutureSessions("Started SKU", now);
    HttpResponse<String> startedOrder = createOrder(user, mixed.skuId(), "started-order-001");
    assertStatus(409, startedOrder);
    assertApiError(startedOrder);

    Fixture soldOut = publishedFixture("Sold out SKU", 0, 100);
    HttpResponse<String> soldOutOrder = createOrder(user, soldOut.skuId(), "sold-out-order-01");
    assertStatus(409, soldOutOrder);
    assertApiError(soldOutOrder);

    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, draft.skuId()));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, mixed.skuId()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, soldOut.skuId()));
  }

  @Test
  void orderOwnershipIsHiddenFromOtherUsersAndAdminAndListsAreSqlPaginated() throws Exception {
    Fixture fixture = publishedFixture("Order ownership", 10, 300);
    long firstId =
        json(createOrder(user, fixture.skuId(), "owner-list-key-001")).path("id").asLong();
    long secondId =
        json(createOrder(user, fixture.skuId(), "owner-list-key-002")).path("id").asLong();
    long thirdId =
        json(createOrder(user, fixture.skuId(), "owner-list-key-003")).path("id").asLong();

    for (String token : List.of(secondUser.token(), admin.token())) {
      for (String suffix : List.of("", "/pay", "/cancel")) {
        HttpResponse<String> hidden =
            call(
                suffix.isEmpty() ? "GET" : "POST",
                "/api/v1/orders/" + firstId + suffix,
                token,
                null);
        if (suffix.isEmpty()) {
          assertStatus(404, hidden);
          assertApiError(hidden);
        } else {
          assertStatus(404, hidden);
          assertApiError(hidden);
        }
      }
    }
    assertEquals(
        "PENDING_PAYMENT",
        jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, firstId));
    assertEquals(
        3L,
        jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Long.class, user.id()));

    HttpResponse<String> page0 = call("GET", "/api/v1/orders?page=0&size=2", user.token(), null);
    HttpResponse<String> page1 = call("GET", "/api/v1/orders?page=1&size=2", user.token(), null);
    HttpResponse<String> empty = call("GET", "/api/v1/orders?page=2&size=2", user.token(), null);
    HttpResponse<String> otherList =
        call("GET", "/api/v1/orders?page=0&size=20", secondUser.token(), null);
    HttpResponse<String> adminList =
        call("GET", "/api/v1/orders?page=0&size=20", admin.token(), null);
    assertStatus(200, page0);
    assertStatus(200, page1);
    assertStatus(200, empty);
    assertStatus(200, otherList);
    assertStatus(200, adminList);
    assertEquals(3, json(page0).path("total").asInt());
    assertEquals(2, json(page0).path("items").size());
    assertEquals(1, json(page1).path("items").size());
    assertEquals(0, json(empty).path("items").size());
    assertEquals(0, json(otherList).path("total").asInt());
    assertEquals(0, json(adminList).path("total").asInt());
    assertEquals(thirdId, json(page0).path("items").path(0).path("id").asLong());
    assertEquals(secondId, json(page0).path("items").path(1).path("id").asLong());
    assertEquals(firstId, json(page1).path("items").path(0).path("id").asLong());

    assertStatus(400, call("GET", "/api/v1/orders/0", user.token(), null));
    HttpResponse<String> invalidPage = call("GET", "/api/v1/orders?page=-1", user.token(), null);
    assertStatus(400, invalidPage);
    assertApiError(invalidPage);
    assertStatus(401, call("GET", "/api/v1/orders", null, null));
    assertStatus(403, call("GET", "/api/v1/orders", organizer.token(), null));
  }

  @Test
  void paymentIssuesOneTicketAndCancelReleasesInventoryExactlyOnce() throws Exception {
    Fixture fixture = publishedFixture("Pay and cancel", 5, 1500);
    HttpResponse<String> pending = createOrder(user, fixture.skuId(), "payment-order-0001");
    long orderId = json(pending).path("id").asLong();
    long itemId = json(pending).path("item").path("id").asLong();
    assertStatus(201, pending);
    assertFalse(json(pending).hasNonNull("ticket"));

    HttpResponse<String> paid =
        call("POST", "/api/v1/orders/" + orderId + "/pay", user.token(), null);
    HttpResponse<String> repeatedPay =
        call("POST", "/api/v1/orders/" + orderId + "/pay", user.token(), null);
    assertStatus(200, paid);
    assertStatus(200, repeatedPay);
    assertEquals("PAID", json(paid).path("status").asText());
    assertEquals(
        json(paid).path("ticket").path("id").asLong(),
        json(repeatedPay).path("ticket").path("id").asLong());
    assertEquals(itemId, json(paid).path("ticket").path("orderItemId").asLong());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets WHERE order_item_id=?", Long.class, itemId));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    HttpResponse<String> cannotCancelPaid =
        call("POST", "/api/v1/orders/" + orderId + "/cancel", user.token(), null);
    assertStatus(409, cannotCancelPaid);
    assertApiError(cannotCancelPaid);

    HttpResponse<String> secondPending = createOrder(user, fixture.skuId(), "cancel-order-0001");
    long secondOrderId = json(secondPending).path("id").asLong();
    HttpResponse<String> cancelled =
        call("POST", "/api/v1/orders/" + secondOrderId + "/cancel", user.token(), null);
    HttpResponse<String> repeatedCancel =
        call("POST", "/api/v1/orders/" + secondOrderId + "/cancel", user.token(), null);
    HttpResponse<String> payClosed =
        call("POST", "/api/v1/orders/" + secondOrderId + "/pay", user.token(), null);
    HttpResponse<String> closedReplay = createOrder(user, fixture.skuId(), "cancel-order-0001");
    assertStatus(200, cancelled);
    assertStatus(200, repeatedCancel);
    assertStatus(409, payClosed);
    assertStatus(200, closedReplay);
    assertEquals("CLOSED", json(cancelled).path("status").asText());
    assertEquals(json(cancelled).path("id").asLong(), json(repeatedCancel).path("id").asLong());
    assertEquals("CLOSED", json(closedReplay).path("status").asText());
    assertFalse(json(cancelled).path("closedAt").isNull());
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class));
  }

  @Test
  void paymentExpiryAndScannerUseInclusiveClockBoundaryAndAreRetryable() throws Exception {
    Fixture fixture = publishedFixture("Expiry scanner", 2, 900);
    HttpResponse<String> created = createOrder(user, fixture.skuId(), "expiry-scanner-001");
    long id = json(created).path("id").asLong();
    Instant expiresAt = Instant.parse(json(created).path("expiresAt").asText());
    assertEquals(Duration.ofMinutes(15), Duration.between(clock.instant(), expiresAt));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));

    clock.set(expiresAt.minusNanos(1));
    assertFalse(orders.closeExpired(id));
    HttpResponse<String> stillPayable =
        call("POST", "/api/v1/orders/" + id + "/pay", user.token(), null);
    assertStatus(200, stillPayable);
    assertEquals("PAID", json(stillPayable).path("status").asText());
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class));

    Fixture expiredFixture = publishedFixture("Expire exact", 1, 900);
    HttpResponse<String> expired =
        createOrder(secondUser, expiredFixture.skuId(), "expiry-exact-key-1");
    long expiredId = json(expired).path("id").asLong();
    Instant exactExpiry = Instant.parse(json(expired).path("expiresAt").asText());
    clock.set(exactExpiry);
    HttpResponse<String> expiredPay =
        call("POST", "/api/v1/orders/" + expiredId + "/pay", secondUser.token(), null);
    assertStatus(409, expiredPay);
    assertApiError(expiredPay);
    assertEquals(
        "PENDING_PAYMENT",
        jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, expiredId),
        "A rejected late payment remains pending until compensation scans it");
    assertEquals(1, expiryScanner.scanOnce());
    assertEquals(0, expiryScanner.scanOnce());
    assertEquals(
        "CLOSED",
        jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, expiredId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            expiredFixture.skuId()));
    HttpResponse<String> closedReplay =
        createOrder(secondUser, expiredFixture.skuId(), "expiry-exact-key-1");
    assertStatus(200, closedReplay);
    assertEquals(expiredId, json(closedReplay).path("id").asLong());
    assertEquals("CLOSED", json(closedReplay).path("status").asText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            expiredFixture.skuId()));
  }

  @Test
  void orderExpirationIsCappedAtSessionStartAndHttpPayCancelRaceHasOneTerminalWinner()
      throws Exception {
    Instant now = clock.instant();
    Fixture startsSoon =
        publishedFixtureAt(
            "Cap expiry", now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofHours(2)), 2, 450);
    HttpResponse<String> capped = createOrder(user, startsSoon.skuId(), "cap-expiry-key-001");
    assertStatus(201, capped);
    assertEquals(startsSoon.startsAt(), Instant.parse(json(capped).path("expiresAt").asText()));

    Fixture race = publishedFixture("Pay cancel race", 1, 450);
    HttpResponse<String> pending = createOrder(secondUser, race.skuId(), "pay-cancel-race-01");
    long id = json(pending).path("id").asLong();
    List<HttpResponse<String>> results =
        parallelCalls(
            2,
            2,
            index ->
                call(
                    "POST",
                    "/api/v1/orders/" + id + (index == 0 ? "/pay" : "/cancel"),
                    secondUser.token(),
                    null));
    assertEquals(1L, results.stream().filter(r -> r.statusCode() == 200).count());
    assertEquals(1L, results.stream().filter(r -> r.statusCode() == 409).count());
    HttpResponse<String> finalState = call("GET", "/api/v1/orders/" + id, secondUser.token(), null);
    assertStatus(200, finalState);
    String status = json(finalState).path("status").asText();
    assertTrue(Set.of("PAID", "CLOSED").contains(status));
    assertEquals(
        "PAID".equals(status) ? 1L : 0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id=?",
            Long.class,
            id));
    assertEquals(
        "PAID".equals(status) ? 0 : 1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, race.skuId()));
  }

  @Test
  void expiryScannerAndConcurrentManualCancelReleaseAnExpiredReservationOnce() throws Exception {
    Fixture fixture = publishedFixture("Cancel scan race", 1, 800);
    HttpResponse<String> created = createOrder(user, fixture.skuId(), "cancel-scan-race-01");
    long id = json(created).path("id").asLong();
    clock.set(Instant.parse(json(created).path("expiresAt").asText()));
    List<Integer> outcomes =
        parallelCallables(
            2, 2, index -> index == 0 ? expiryScanner.scanOnce() : cancelAndReadStatus(id));
    assertTrue(outcomes.get(0) == 0 || outcomes.get(0) == 1);
    assertEquals(
        200, outcomes.get(1), "Repeated cancellation after scanner closure remains an HTTP 200");
    assertEquals(
        "CLOSED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class));
    assertEquals(0, expiryScanner.scanOnce());

    HttpResponse<String> payRaceOrder = createOrder(user, fixture.skuId(), "scan-pay-race-key-1");
    long payRaceId = json(payRaceOrder).path("id").asLong();
    clock.set(Instant.parse(json(payRaceOrder).path("expiresAt").asText()));
    List<Integer> payRace =
        parallelCallables(
            2,
            2,
            index -> {
              if (index == 0) {
                return expiryScanner.scanOnce();
              }
              HttpResponse<String> latePay =
                  call("POST", "/api/v1/orders/" + payRaceId + "/pay", user.token(), null);
              assertStatus(409, latePay);
              assertApiError(latePay);
              return latePay.statusCode();
            });
    assertEquals(1, payRace.get(0));
    assertEquals(409, payRace.get(1));
    assertEquals(
        "CLOSED",
        jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, payRaceId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class));
    assertEquals(0, expiryScanner.scanOnce());
  }

  @Test
  void orderAndTicketIdorIsHiddenAndOrganizerOnlySeesTheirOwnCheckinTarget() throws Exception {
    Fixture first = publishedFixture("Ticket owner scope", 2, 1000);
    Fixture second =
        publishedFixtureForOrganizer("Other organizer ticket", secondOrganizer, 2, 1000);
    HttpResponse<String> firstOrder = createOrder(user, first.skuId(), "ticket-owner-key-001");
    long firstOrderId = json(firstOrder).path("id").asLong();
    HttpResponse<String> firstPaid =
        call("POST", "/api/v1/orders/" + firstOrderId + "/pay", user.token(), null);
    assertStatus(200, firstPaid);
    long ticketId = json(firstPaid).path("ticket").path("id").asLong();
    HttpResponse<String> secondOrder =
        createOrder(secondUser, second.skuId(), "ticket-second-key-01");
    long secondOrderId = json(secondOrder).path("id").asLong();
    HttpResponse<String> secondPaid =
        call("POST", "/api/v1/orders/" + secondOrderId + "/pay", secondUser.token(), null);
    assertStatus(200, secondPaid);
    long otherTicketId = json(secondPaid).path("ticket").path("id").asLong();

    for (String token : List.of(secondUser.token(), admin.token())) {
      HttpResponse<String> hiddenOrder = call("GET", "/api/v1/orders/" + firstOrderId, token, null);
      HttpResponse<String> hiddenTicketCode =
          call("POST", "/api/v1/me/tickets/" + ticketId + "/checkin-code", token, null);
      assertStatus(404, hiddenOrder);
      assertStatus(404, hiddenTicketCode);
      assertApiError(hiddenOrder);
      assertApiError(hiddenTicketCode);
    }
    HttpResponse<String> userCheckInForbidden =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            user.token(),
            Map.of("checkinCode", "A".repeat(43)));
    assertStatus(403, userCheckInForbidden);
    assertApiError(userCheckInForbidden);
    HttpResponse<String> otherOrganizerCode =
        call(
            "POST",
            "/api/v1/me/tickets/" + otherTicketId + "/checkin-code",
            organizer.token(),
            null);
    assertStatus(403, otherOrganizerCode);
    assertApiError(otherOrganizerCode);

    HttpResponse<String> userTickets =
        call("GET", "/api/v1/me/tickets?page=0&size=20", user.token(), null);
    HttpResponse<String> otherTickets =
        call("GET", "/api/v1/me/tickets?page=0&size=20", secondUser.token(), null);
    assertStatus(200, userTickets);
    assertStatus(200, otherTickets);
    assertEquals(1, json(userTickets).path("total").asInt());
    assertEquals(ticketId, json(userTickets).path("items").path(0).path("id").asLong());
    assertEquals(1, json(otherTickets).path("total").asInt());
    assertEquals(otherTicketId, json(otherTickets).path("items").path(0).path("id").asLong());

    HttpResponse<String> otherOwnerCode =
        call(
            "POST",
            "/api/v1/me/tickets/" + otherTicketId + "/checkin-code",
            secondUser.token(),
            null);
    assertStatus(200, otherOwnerCode);
    String adminCheckinCode = json(otherOwnerCode).path("checkinCode").asText();
    clock.set(second.startsAt().minus(Duration.ofHours(2)));
    HttpResponse<String> adminCheckin =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            admin.token(),
            Map.of("checkinCode", adminCheckinCode));
    assertStatus(200, adminCheckin);
    assertEquals(otherTicketId, json(adminCheckin).path("ticketId").asLong());
    assertEquals(
        admin.id(),
        jdbc.queryForObject(
            "SELECT operator_user_id FROM checkin_records WHERE ticket_id=?",
            Long.class,
            otherTicketId));
  }

  @Test
  void checkinCodeIsHighEntropyHashedRotatesAndHonorsOwnershipWindowAndOneTimeUse()
      throws Exception {
    Instant origin = clock.instant();
    Fixture fixture =
        publishedFixtureAt(
            "Ticket window",
            origin.plus(Duration.ofHours(2)),
            origin.plus(Duration.ofHours(3)),
            2,
            1000);
    HttpResponse<String> created = createOrder(user, fixture.skuId(), "ticket-window-key-01");
    long orderId = json(created).path("id").asLong();
    HttpResponse<String> paid =
        call("POST", "/api/v1/orders/" + orderId + "/pay", user.token(), null);
    assertStatus(200, paid);
    long ticketId = json(paid).path("ticket").path("id").asLong();

    HttpResponse<String> firstCodeResponse =
        call("POST", "/api/v1/me/tickets/" + ticketId + "/checkin-code", user.token(), null);
    HttpResponse<String> rotatedCodeResponse =
        call("POST", "/api/v1/me/tickets/" + ticketId + "/checkin-code", user.token(), null);
    assertStatus(200, firstCodeResponse);
    assertStatus(200, rotatedCodeResponse);
    String oldCode = json(firstCodeResponse).path("checkinCode").asText();
    String currentCode = json(rotatedCodeResponse).path("checkinCode").asText();
    assertEquals(ticketId, json(rotatedCodeResponse).path("ticketId").asLong());
    assertTrue(oldCode.matches("[A-Za-z0-9_-]{43}"));
    assertTrue(currentCode.matches("[A-Za-z0-9_-]{43}"));
    assertTrue(
        !oldCode.equals(currentCode), "Code rotation must create a distinct one-time credential");
    String storedHash =
        jdbc.queryForObject(
            "SELECT checkin_code_hash FROM tickets WHERE id=?", String.class, ticketId);
    assertTrue(
        sha256Hex(currentCode).equals(storedHash), "Only the SHA-256 digest may be persisted");
    assertFalse(storedHash.equals(currentCode));

    HttpResponse<String> malformed =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", "bad"));
    String unknownCanonicalCode =
        Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    HttpResponse<String> unknownCode =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", unknownCanonicalCode));
    HttpResponse<String> oldCodeRejected =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", oldCode));
    assertStatus(400, malformed);
    assertStatus(404, unknownCode);
    assertStatus(404, oldCodeRejected);
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_records", Long.class));

    clock.set(fixture.startsAt().minus(Duration.ofHours(2)).minusSeconds(1));
    HttpResponse<String> tooEarly =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", currentCode));
    assertStatus(409, tooEarly);
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_records", Long.class));

    clock.set(fixture.startsAt().minus(Duration.ofHours(2)));
    HttpResponse<String> wrongOrganizer =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            secondOrganizer.token(),
            Map.of("checkinCode", currentCode));
    assertStatus(404, wrongOrganizer);
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_records", Long.class));

    List<HttpResponse<String>> sameCode =
        parallelCalls(
            2,
            2,
            ignored ->
                call(
                    "POST",
                    "/api/v1/organizer/tickets/check-in",
                    organizer.token(),
                    Map.of("checkinCode", currentCode)));
    assertEquals(1L, sameCode.stream().filter(response -> response.statusCode() == 200).count());
    assertEquals(1L, sameCode.stream().filter(response -> response.statusCode() == 409).count());
    HttpResponse<String> repeated =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", currentCode));
    assertStatus(409, repeated);
    assertEquals(
        "USED",
        jdbc.queryForObject("SELECT status FROM tickets WHERE id=?", String.class, ticketId));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM checkin_records WHERE ticket_id=? AND result='SUCCESS'",
            Long.class,
            ticketId));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_records", Long.class));
    JsonNode success =
        json(sameCode.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow());
    assertEquals(ticketId, success.path("ticketId").asLong());
    assertEquals("USED", success.path("status").asText());
    assertTrue(success.hasNonNull("usedAt"));
  }

  @Test
  void checkinWindowIncludesEndPlusOneHourAndRejectsUnpaidTicketsAndUsedCodes() throws Exception {
    Instant origin = clock.instant();
    Fixture boundary =
        publishedFixtureAt(
            "Window end",
            origin.plus(Duration.ofHours(5)),
            origin.plus(Duration.ofHours(6)),
            2,
            1000);
    HttpResponse<String> unpaidPending = createOrder(user, boundary.skuId(), "unpaid-ticket-key01");
    long unpaidItemId = json(unpaidPending).path("item").path("id").asLong();
    String unpaidCode = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    jdbc.update(
        "INSERT INTO tickets(order_item_id,owner_user_id,checkin_code_hash,status) VALUES (?,?,?,'UNUSED')",
        unpaidItemId,
        user.id(),
        sha256Hex(unpaidCode));
    HttpResponse<String> unpaidCodeRequest =
        call(
            "POST",
            "/api/v1/me/tickets/"
                + jdbc.queryForObject(
                    "SELECT id FROM tickets WHERE order_item_id=?", Long.class, unpaidItemId)
                + "/checkin-code",
            user.token(),
            null);
    HttpResponse<String> unpaidTicketList =
        call("GET", "/api/v1/me/tickets?page=0&size=20", user.token(), null);
    assertStatus(409, unpaidCodeRequest);
    assertApiError(unpaidCodeRequest);
    assertStatus(200, unpaidTicketList);
    assertEquals(0, json(unpaidTicketList).path("total").asInt());
    HttpResponse<String> unpaidAttempt =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", unpaidCode));
    assertStatus(409, unpaidAttempt);
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_records", Long.class));
    assertEquals(
        "UNUSED",
        jdbc.queryForObject(
            "SELECT status FROM tickets WHERE order_item_id=?", String.class, unpaidItemId));

    HttpResponse<String> paidPending =
        createOrder(secondUser, boundary.skuId(), "paid-ticket-key-0001");
    long orderId = json(paidPending).path("id").asLong();
    HttpResponse<String> paid =
        call("POST", "/api/v1/orders/" + orderId + "/pay", secondUser.token(), null);
    assertStatus(200, paid);
    long ticketId = json(paid).path("ticket").path("id").asLong();
    HttpResponse<String> codeResponse =
        call("POST", "/api/v1/me/tickets/" + ticketId + "/checkin-code", secondUser.token(), null);
    assertStatus(200, codeResponse);
    String code = json(codeResponse).path("checkinCode").asText();
    clock.set(boundary.endsAt().plus(Duration.ofHours(1)));
    HttpResponse<String> boundaryCheckin =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", code));
    assertStatus(200, boundaryCheckin);
    assertEquals("USED", json(boundaryCheckin).path("status").asText());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM checkin_records WHERE ticket_id=?", Long.class, ticketId));
    HttpResponse<String> usedAgain =
        call(
            "POST",
            "/api/v1/organizer/tickets/check-in",
            organizer.token(),
            Map.of("checkinCode", code));
    assertStatus(409, usedAgain);
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM checkin_records", Long.class));
  }

  @Test
  void thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact()
      throws Exception {
    final int requestCount = 1000;
    final int initialStock = 100;
    Fixture fixture = publishedFixture("Distinct user saturation", initialStock, 25);
    String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String emailPrefix = "s2load-" + runId + "-";
    List<Object[]> users = new ArrayList<>(requestCount);
    for (int i = 0; i < requestCount; i++) {
      users.add(new Object[] {emailPrefix + i + "@example.test", PASSWORD_HASH});
    }
    jdbc.batchUpdate("INSERT INTO users(email,password_hash,status) VALUES (?,?,'ACTIVE')", users);
    List<Long> userIds =
        jdbc.query(
            "SELECT id FROM users WHERE email LIKE ? ORDER BY id",
            (rs, row) -> rs.getLong(1),
            emailPrefix + "%");
    assertEquals(requestCount, userIds.size());
    List<Object[]> roles = new ArrayList<>(requestCount);
    userIds.forEach(id -> roles.add(new Object[] {id, "USER"}));
    jdbc.batchUpdate("INSERT INTO user_roles(user_id,role) VALUES (?,?)", roles);

    List<LoadRequest> requests = new ArrayList<>(requestCount);
    for (int index = 0; index < requestCount; index++) {
      requests.add(
          new LoadRequest(
              jwt(userIds.get(index), List.of("USER"), Instant.now().plusSeconds(1800)),
              "load-" + runId + "-" + index));
    }
    assertEquals(
        requestCount, requests.stream().map(LoadRequest::idempotencyKey).distinct().count());

    AtomicInteger readyToSendWorkers = new AtomicInteger();
    AtomicInteger maxReadyToSendWorkers = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(CONCURRENCY_WORKERS);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch readyToSend = new CountDownLatch(CONCURRENCY_WORKERS);
    CountDownLatch sendNow = new CountDownLatch(1);
    AtomicInteger workerNumber = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(CONCURRENCY_WORKERS);
    List<CompletableFuture<LoadResult>> futures = new ArrayList<>(requestCount);
    try {
      for (int index = 0; index < requestCount; index++) {
        final int requestIndex = index;
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  int worker = workerNumber.getAndIncrement();
                  boolean barrierParticipant = worker < CONCURRENCY_WORKERS;
                  boolean countedReady = false;
                  if (barrierParticipant) {
                    ready.countDown();
                  }
                  try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                      throw new AssertionError("Concurrent request barrier timed out");
                    }
                    if (barrierParticipant) {
                      int readyCount = readyToSendWorkers.incrementAndGet();
                      countedReady = true;
                      maxReadyToSendWorkers.accumulateAndGet(readyCount, Math::max);
                      readyToSend.countDown();
                      if (!sendNow.await(60, TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent HTTP send barrier timed out");
                      }
                    }
                    HttpResponse<String> response =
                        call(
                            "POST",
                            "/api/v1/orders",
                            requests.get(requestIndex).token(),
                            orderRequest(
                                fixture.skuId(), requests.get(requestIndex).idempotencyKey()));
                    String code = response.statusCode() == 409 ? errorCode(response) : "";
                    return new LoadResult(response.statusCode(), code);
                  } catch (Exception exception) {
                    throw new CompletionException(exception);
                  } finally {
                    if (countedReady) {
                      readyToSendWorkers.decrementAndGet();
                    }
                  }
                },
                pool));
      }
      assertTrue(
          ready.await(60, TimeUnit.SECONDS), "All configured worker threads must rendezvous");
      release.countDown();
      assertTrue(
          readyToSend.await(60, TimeUnit.SECONDS),
          "All worker threads must be ready to send HTTP requests");
      sendNow.countDown();
      List<LoadResult> results = futures.stream().map(CompletableFuture::join).toList();
      assertEquals(requestCount, results.size(), "All distinct-user requests must finish");

      long created = results.stream().filter(result -> result.status() == 201).count();
      long conflicts = results.stream().filter(result -> result.status() == 409).count();
      Map<String, Long> conflictCodes = new HashMap<>();
      results.stream()
          .filter(result -> result.status() == 409)
          .forEach(result -> conflictCodes.merge(result.code(), 1L, Long::sum));
      long finalStock =
          jdbc.queryForObject(
              "SELECT available_stock FROM ticket_skus WHERE id=?", Long.class, fixture.skuId());
      long pendingOrders =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM orders WHERE requested_sku_id=? AND status='PENDING_PAYMENT'",
              Long.class,
              fixture.skuId());
      long items =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM order_items WHERE sku_id=?", Long.class, fixture.skuId());
      long quantity =
          jdbc.queryForObject(
              "SELECT COALESCE(SUM(quantity),0) FROM order_items WHERE sku_id=?",
              Long.class,
              fixture.skuId());
      long distinctOwners =
          jdbc.queryForObject(
              "SELECT COUNT(DISTINCT user_id) FROM orders WHERE requested_sku_id=?",
              Long.class,
              fixture.skuId());
      Map<String, Object> artifact =
          Map.ofEntries(
              Map.entry("schema", "eventflow.stage2.concurrency-result.v1"),
              Map.entry("mysqlImage", MYSQL_IMAGE),
              Map.entry("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class)),
              Map.entry("storage", "disposable Testcontainers MySQL data on 1 GiB tmpfs"),
              Map.entry("timestampUtc", Instant.now().toString()),
              Map.entry(
                  "fixture",
                  "1000 distinct ACTIVE USER rows and roles inserted with SQL; each request used a separately signed real HS256 JWT"),
              Map.entry("credentialMaterialIncluded", false),
              Map.entry("userCount", requestCount),
              Map.entry("requestCount", requestCount),
              Map.entry("distinctUsers", userIds.size()),
              Map.entry(
                  "distinctIdempotencyKeys",
                  requests.stream().map(LoadRequest::idempotencyKey).distinct().count()),
              Map.entry("workerPoolSize", CONCURRENCY_WORKERS),
              Map.entry("barrierParticipants", CONCURRENCY_WORKERS),
              Map.entry("maxClientRequestWorkersReadyToSend", maxReadyToSendWorkers.get()),
              Map.entry("skuInitialStock", initialStock),
              Map.entry("http201Created", created),
              Map.entry("http409Conflict", conflicts),
              Map.entry("conflictCodes", conflictCodes),
              Map.entry("completedRequests", results.size()),
              Map.entry("databaseRemainingStock", finalStock),
              Map.entry("databasePendingOrders", pendingOrders),
              Map.entry("databaseOrderItems", items),
              Map.entry("databaseItemQuantity", quantity),
              Map.entry("databaseDistinctOrderOwners", distinctOwners));
      writeConcurrencyArtifact(artifact);

      assertTrue(
          conflictCodes.keySet().stream().allMatch(Set.of("SOLD_OUT", "SKU_UNAVAILABLE")::contains),
          "Every unsuccessful reservation must be a real stock/availability conflict");
      assertEquals(
          48, maxReadyToSendWorkers.get(), "The 48 worker requests must be released concurrently");
      assertEquals(100, created);
      assertEquals(900, conflicts);
      assertEquals(requestCount, results.size());
      assertEquals(0, finalStock);
      assertEquals(initialStock, pendingOrders);
      assertEquals(initialStock, items);
      assertEquals(initialStock, quantity);
      assertEquals(initialStock, distinctOwners);
      assertTrue(
          jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ticket_skus WHERE available_stock<0 OR available_stock>total_stock",
                  Long.class)
              == 0);
    } finally {
      release.countDown();
      sendNow.countDown();
      pool.shutdownNow();
    }
  }

  private void verifyFreshV1ToV2Schema() {
    assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1",
            Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1",
            Long.class));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
  }

  private long countIndex(String table, String index, boolean unique) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=? AND index_name=? AND non_unique=?
        """,
        Long.class,
        table,
        index,
        unique ? 0 : 1);
  }

  private long countConstraint(String constraint) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE constraint_schema=DATABASE() AND constraint_name=? AND constraint_type='CHECK'
        """,
        Long.class,
        constraint);
  }

  private Account insertAccount(String role) throws Exception {
    String email = "stage2-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
    jdbc.update(
        "INSERT INTO users(email,password_hash,status) VALUES (?,?,'ACTIVE')",
        email,
        PASSWORD_HASH);
    long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    jdbc.update("INSERT INTO user_roles(user_id,role) VALUES (?,?)", id, role);
    return new Account(id, jwt(id, List.of(role), Instant.now().plusSeconds(3600)));
  }

  private Fixture publishedFixture(String title, int stock, long price) throws Exception {
    Instant starts = clock.instant().plus(Duration.ofHours(4));
    return publishedFixtureAt(title, starts, starts.plus(Duration.ofHours(2)), stock, price);
  }

  private Fixture publishedFixtureAt(
      String title, Instant starts, Instant ends, int stock, long price) throws Exception {
    return eventFixture(organizer, title, starts, ends, stock, price, true);
  }

  private Fixture publishedFixtureForOrganizer(
      String title, Account eventOrganizer, int stock, long price) throws Exception {
    Instant starts = clock.instant().plus(Duration.ofHours(6));
    return eventFixture(
        eventOrganizer, title, starts, starts.plus(Duration.ofHours(2)), stock, price, true);
  }

  private Fixture draftFixture(String title, int stock, long price) throws Exception {
    Instant starts = clock.instant().plus(Duration.ofHours(4));
    return eventFixture(
        organizer, title, starts, starts.plus(Duration.ofHours(2)), stock, price, false);
  }

  private Fixture eventFixture(
      Account eventOrganizer,
      String title,
      Instant starts,
      Instant ends,
      int stock,
      long price,
      boolean publish)
      throws Exception {
    long eventId = createEvent(eventOrganizer, title);
    long sessionId = addSession(eventOrganizer, eventId, "Session", starts, ends);
    long skuId = addSku(eventOrganizer, sessionId, "General", price, stock);
    if (publish) {
      assertStatus(
          200,
          call(
              "POST",
              "/api/v1/organizer/events/" + eventId + "/publish",
              eventOrganizer.token(),
              null));
    }
    return new Fixture(eventId, sessionId, skuId, starts, ends);
  }

  private Fixture publishedEventWithStartedAndFutureSessions(String title, Instant now)
      throws Exception {
    long eventId = createEvent(organizer, title);
    long startedSession =
        addSession(
            organizer,
            eventId,
            "Already started",
            now.minus(Duration.ofHours(2)),
            now.minus(Duration.ofHours(1)));
    long startedSku = addSku(organizer, startedSession, "Started", 100, 2);
    long futureSession =
        addSession(
            organizer,
            eventId,
            "Still future",
            now.plus(Duration.ofHours(3)),
            now.plus(Duration.ofHours(5)));
    addSku(organizer, futureSession, "Future", 100, 2);
    assertStatus(
        200,
        call("POST", "/api/v1/organizer/events/" + eventId + "/publish", organizer.token(), null));
    return new Fixture(
        eventId,
        startedSession,
        startedSku,
        now.minus(Duration.ofHours(2)),
        now.minus(Duration.ofHours(1)));
  }

  private TwoSkuFixture twoSkuPublishedFixture(String title, int stock) throws Exception {
    Instant starts = clock.instant().plus(Duration.ofHours(4));
    long eventId = createEvent(organizer, title);
    long sessionId =
        addSession(organizer, eventId, "Session", starts, starts.plus(Duration.ofHours(2)));
    long first = addSku(organizer, sessionId, "General A", 100, stock);
    long second = addSku(organizer, sessionId, "General B", 125, stock);
    assertStatus(
        200,
        call("POST", "/api/v1/organizer/events/" + eventId + "/publish", organizer.token(), null));
    return new TwoSkuFixture(eventId, first, second);
  }

  private long createEvent(Account eventOrganizer, String title) throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/events",
            eventOrganizer.token(),
            Map.of("title", title, "description", "Stage 2 real MySQL integration fixture"));
    assertStatus(201, response);
    return json(response).path("id").asLong();
  }

  private long addSession(
      Account eventOrganizer, long eventId, String title, Instant start, Instant end)
      throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/events/" + eventId + "/sessions",
            eventOrganizer.token(),
            Map.of(
                "title",
                title,
                "startsAt",
                start.toString(),
                "endsAt",
                end.toString(),
                "location",
                "Main Hall"));
    assertStatus(201, response);
    return json(response).path("id").asLong();
  }

  private long addSku(Account eventOrganizer, long sessionId, String name, long price, int stock)
      throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/sessions/" + sessionId + "/ticket-skus",
            eventOrganizer.token(),
            Map.of("name", name, "priceCents", price, "totalStock", stock));
    assertStatus(201, response);
    return json(response).path("id").asLong();
  }

  private HttpResponse<String> createOrder(Account account, long skuId, String idempotencyKey)
      throws Exception {
    return call("POST", "/api/v1/orders", account.token(), orderRequest(skuId, idempotencyKey));
  }

  private static Map<String, Object> orderRequest(long skuId, String key) {
    return Map.of("skuId", skuId, "idempotencyKey", key);
  }

  private List<HttpResponse<String>> parallelCalls(
      int count, int workers, ThrowingIndexedFunction<HttpResponse<String>> operation)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger number = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(workers);
    List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>(count);
    try {
      for (int index = 0; index < count; index++) {
        final int value = index;
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  if (number.getAndIncrement() < workers) {
                    ready.countDown();
                  }
                  try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                      throw new AssertionError("Concurrent request barrier timed out");
                    }
                    return operation.apply(value);
                  } catch (Exception exception) {
                    throw new CompletionException(exception);
                  }
                },
                pool));
      }
      assertTrue(ready.await(30, TimeUnit.SECONDS));
      release.countDown();
      return futures.stream().map(CompletableFuture::join).toList();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private List<Integer> parallelCallables(
      int count, int workers, ThrowingIndexedFunction<Integer> operation) throws Exception {
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger number = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(workers);
    List<CompletableFuture<Integer>> futures = new ArrayList<>(count);
    try {
      for (int index = 0; index < count; index++) {
        final int value = index;
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  if (number.getAndIncrement() < workers) {
                    ready.countDown();
                  }
                  try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                      throw new AssertionError("Concurrent operation barrier timed out");
                    }
                    return operation.apply(value);
                  } catch (Exception exception) {
                    throw new CompletionException(exception);
                  }
                },
                pool));
      }
      assertTrue(ready.await(30, TimeUnit.SECONDS));
      release.countDown();
      return futures.stream().map(CompletableFuture::join).toList();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private int cancelAndReadStatus(long id) throws Exception {
    HttpResponse<String> response =
        call("POST", "/api/v1/orders/" + id + "/cancel", user.token(), null);
    assertTrue(response.statusCode() == 200 || response.statusCode() == 409);
    if (response.statusCode() == 200) {
      assertEquals("CLOSED", json(response).path("status").asText());
    }
    if (response.statusCode() == 409) {
      assertApiError(response);
    }
    return response.statusCode();
  }

  private HttpResponse<String> call(String method, String path, String token, Object body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(60))
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

  private static JsonNode json(HttpResponse<String> response) {
    try {
      return JSON.readTree(response.body());
    } catch (Exception exception) {
      throw new AssertionError(
          "Expected JSON for HTTP "
              + response.statusCode()
              + " content-type="
              + response.headers().firstValue("content-type").orElse("<missing>"),
          exception);
    }
  }

  private static String errorCode(HttpResponse<String> response) {
    assertApiError(response);
    return json(response).path("code").asText();
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

  private static MySQLContainer<?> mysqlContainer(String database) {
    return new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName(database)
        .withCommand("mysqld", "--innodb-redo-log-capacity=128M")
        .withTmpFs(Map.of("/var/lib/mysql", "rw,noexec,nosuid,size=1g"))
        .withStartupTimeoutSeconds(300)
        .withUsername("eventflow_test")
        .withPassword("eventflow_test");
  }

  private static String jwt(long subject, List<String> roles, Instant expiration) throws Exception {
    long now = Instant.now().getEpochSecond();
    String header = base64Url(JSON.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
    String payload =
        base64Url(
            JSON.writeValueAsBytes(
                Map.of(
                    "iss", ISSUER,
                    "sub", Long.toString(subject),
                    "iat", now,
                    "exp", expiration.getEpochSecond(),
                    "roles", roles)));
    String signingContent = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return signingContent
        + "."
        + base64Url(mac.doFinal(signingContent.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String sha256Hex(String value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static String randomHexSecret() {
    return java.util.HexFormat.of().formatHex(randomBytes(32));
  }

  private static void writeConcurrencyArtifact(Map<String, Object> artifact) throws IOException {
    Path output = Path.of("target", "stage-2-concurrency-result.json");
    Files.createDirectories(output.getParent());
    Files.writeString(
        output, JSON.writeValueAsString(artifact) + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  private record Account(long id, String token) {}

  private record Fixture(
      long eventId, long sessionId, long skuId, Instant startsAt, Instant endsAt) {}

  private record TwoSkuFixture(long eventId, long firstSkuId, long secondSkuId) {}

  private record LoadRequest(String token, String idempotencyKey) {}

  private record LoadResult(int status, String code) {}

  @FunctionalInterface
  private interface ThrowingIndexedFunction<T> {
    T apply(int index) throws Exception;
  }

  private static final class RollbackMarker extends RuntimeException {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableTestClock stage2MutableClock() {
      return new MutableTestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
  }

  static final class MutableTestClock extends Clock {
    private final AtomicReference<Instant> instant;

    MutableTestClock(Instant initial) {
      this.instant = new AtomicReference<>(initial);
    }

    void set(Instant value) {
      instant.set(value);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("Stage 2 integration tests use UTC only");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
