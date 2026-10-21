package com.eventflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eventflow.cache.EventCacheMetrics;
import com.eventflow.event.application.EventService;
import com.eventflow.event.application.PublishedEventCacheData.CachedSession;
import com.eventflow.event.application.PublishedEventCacheData.CachedSku;
import com.eventflow.event.application.PublishedEventCacheData.Detail;
import com.eventflow.event.infrastructure.RedisPublishedEventCache;
import com.eventflow.messaging.application.OrderExpiryMessageHandler;
import com.eventflow.messaging.application.OutboxClaimService;
import com.eventflow.messaging.application.OutboxPublisher;
import com.eventflow.messaging.config.MessagingNames;
import com.eventflow.messaging.domain.OutboxClaim;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Stage 3 end-to-end reliability tests using real MySQL, Redis, RabbitMQ and HTTP. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.orders.expiry-scan-enabled=false",
      "app.orders.expiry-retry-base-ms=1000",
      "app.orders.expiry-retry-max-ms=300000",
      "app.events.cache.enabled=true",
      "app.events.cache.list-ttl-ms=60000",
      "app.events.cache.detail-ttl-ms=300000",
      "app.events.cache.ttl-jitter-ms=15000",
      "app.rate-limit.orders.enabled=true",
      "app.rate-limit.orders.limit=3",
      "app.rate-limit.orders.window-ms=5000",
      "app.messaging.enabled=true",
      "app.messaging.publisher-schedule-enabled=false",
      "app.messaging.publisher-batch-size=50",
      "app.messaging.claim-lease-ms=5000",
      "app.messaging.confirm-timeout-ms=250",
      "app.messaging.publish-backoff-base-ms=100",
      "app.messaging.publish-backoff-max-ms=500",
      "app.messaging.expiry-listener-enabled=true",
      "app.messaging.notification-listener-enabled=true",
      "app.messaging.consumer-max-retries=2",
      "app.messaging.consumer-retry-delay-ms=100"
    })
@Import(Stage3IntegrationTest.ClockConfiguration.class)
class Stage3IntegrationTest {
  private static final String MYSQL_IMAGE =
      "mysql@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364";
  private static final String REDIS_IMAGE =
      "redis@sha256:0514fa59e3d84e2f3be66731c5401fc2a85b8ea71b49c0116c850f6a558076f4";
  private static final String RABBIT_IMAGE =
      "rabbitmq@sha256:3505b6096cdbc083bcc363e74366f575ecd0c1a55be4dadcbc60fa08e34647e1";
  private static final String ISSUER = "eventflow";
  private static final String PASSWORD_HASH = "unused-stage3-fixture-password-hash";
  private static final String EXPIRY_LISTENER = "eventflow-order-expiry-v1";
  private static final String NOTIFICATION_LISTENER = "eventflow-notification-v1";
  private static final String NOTIFICATION_TRIGGER = "stage3_fail_notification_insert";
  private static final String OUTBOX_TRIGGER = "stage3_fail_outbox_insert";
  private static final String JWT_SECRET = randomHexSecret();
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private static final AtomicInteger FRESH_SCHEMA_CHECKED = new AtomicInteger();
  private static final List<Map<String, Object>> EVIDENCE = new CopyOnWriteArrayList<>();
  private static final int HTTP_WORKERS = 16;
  private static final List<String> ALL_QUEUES =
      List.of(
          MessagingNames.ORDER_EXPIRY_DELAY_QUEUE,
          MessagingNames.ORDER_EXPIRY_QUEUE,
          MessagingNames.ORDER_EXPIRY_RETRY_QUEUE,
          MessagingNames.NOTIFICATION_QUEUE,
          MessagingNames.NOTIFICATION_RETRY_QUEUE,
          MessagingNames.DEAD_LETTER_QUEUE);
  private static volatile String OBSERVED_MYSQL_VERSION = "unobserved";

  @Container private static final MySQLContainer<?> MYSQL = mysqlContainer("eventflow_stage3_test");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
          .withExposedPorts(6379)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));

  @Container
  private static final GenericContainer<?> RABBIT =
      new GenericContainer<>(DockerImageName.parse(RABBIT_IMAGE))
          .withEnv("RABBITMQ_DEFAULT_USER", "eventflow_test")
          .withEnv("RABBITMQ_DEFAULT_PASS", "eventflow_test")
          .withExposedPorts(5672)
          .waitingFor(
              new WaitAllStrategy()
                  .withStrategy(Wait.forListeningPort())
                  .withStrategy(Wait.forLogMessage(".*Server startup complete.*", 1)))
          .withStartupTimeout(Duration.ofSeconds(180));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("app.jwt.secret", () -> JWT_SECRET);
    registry.add("app.jwt.issuer", () -> ISSUER);
    registry.add("app.jwt.ttl-seconds", () -> 3600);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.connect-timeout", () -> "300ms");
    registry.add("spring.data.redis.timeout", () -> "300ms");
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
    registry.add("spring.rabbitmq.username", () -> "eventflow_test");
    registry.add("spring.rabbitmq.password", () -> "eventflow_test");
    registry.add("spring.rabbitmq.connection-timeout", () -> "2s");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableTestClock clock;
  @Autowired private EventService events;
  @Autowired private RedisPublishedEventCache publishedEventCache;
  @Autowired private OrderService orders;
  @Autowired private OrderExpiryScanner expiryScanner;
  @Autowired private OutboxPublisher outboxPublisher;
  @Autowired private OutboxClaimService outboxClaims;
  @Autowired private OrderExpiryMessageHandler expiryMessages;
  @Autowired private EventCacheMetrics cacheMetrics;
  @Autowired private StringRedisTemplate redis;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private RabbitListenerEndpointRegistry listenerRegistry;
  @Autowired private PlatformTransactionManager transactionManager;
  @LocalServerPort private int port;

  private Account user;
  private Account secondUser;
  private Account organizer;
  private Account secondOrganizer;
  private Account admin;

  @BeforeEach
  void resetDatabaseBrokerAndRedis() throws Exception {
    clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    stopListener(EXPIRY_LISTENER);
    stopListener(NOTIFICATION_LISTENER);
    for (String queue : ALL_QUEUES) {
      if (rabbitAdmin.getQueueInfo(queue) != null) {
        rabbitAdmin.purgeQueue(queue);
      }
    }
    assertEquals("OK", REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE").getStdout().trim());
    assertEquals("OK", REDIS.execInContainer("redis-cli", "FLUSHDB").getStdout().trim());

    if (FRESH_SCHEMA_CHECKED.compareAndSet(0, 1)) {
      verifyFreshV1V2V3SchemaAndNoSeedUsers();
    }
    jdbc.execute("DROP TRIGGER IF EXISTS " + NOTIFICATION_TRIGGER);
    jdbc.execute("DROP TRIGGER IF EXISTS " + OUTBOX_TRIGGER);
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
    cacheMetrics.reset();
    startListener(EXPIRY_LISTENER);
    startListener(NOTIFICATION_LISTENER);
  }

  @AfterAll
  static void writeFaultEvidence() throws IOException {
    var artifact =
        Map.of(
            "schema",
            "eventflow.stage3.fault-results.v1",
            "mysqlImage",
            MYSQL_IMAGE,
            "mysqlVersion",
            OBSERVED_MYSQL_VERSION,
            "redisImage",
            REDIS_IMAGE,
            "redisVersion",
            "8.4.7-alpine",
            "rabbitImage",
            RABBIT_IMAGE,
            "rabbitVersion",
            "4.3.6-management-alpine",
            "credentialMaterialIncluded",
            false,
            "throughputOrQpsClaimed",
            false,
            "scenarios",
            List.copyOf(EVIDENCE));
    Path output = Path.of("target", "stage-3-fault-results.json");
    Files.createDirectories(output.getParent());
    Files.writeString(
        output, JSON.writeValueAsString(artifact) + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  @Test
  void freshSchemaAndV1ToV2ToV3UpgradePreserveUserEventOrderAndTicket() throws Exception {
    assertTrue(mysqlVersion().startsWith("8.4"));
    assertEquals(1L, versionCount("1"));
    assertEquals(1L, versionCount("2"));
    assertEquals(1L, versionCount("3"));
    assertEquals(
        12L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('users','user_roles','events','event_sessions','ticket_skus','orders','order_items','tickets','checkin_records','outbox_events','consumed_events','user_notifications')",
            Long.class));
    assertEquals(1L, countIndex("outbox_events", "idx_outbox_due", false));
    assertEquals(1L, countIndex("outbox_events", "idx_outbox_lease", false));
    assertEquals(1L, countIndex("user_notifications", "uq_notifications_event", true));
    assertEquals(1L, countIndex("orders", "idx_orders_expiry_retry", false));
    assertEquals(1L, countConstraint("ck_orders_expiry_retry"));
    assertEquals(1L, countConstraint("ck_outbox_status"));
    assertEquals(1L, countConstraint("ck_outbox_type"));
    assertEquals(1L, countConstraint("ck_outbox_lease"));
    try (MySQLContainer<?> upgradeDb = mysqlContainer("eventflow_stage3_upgrade_test")) {
      upgradeDb.start();
      Flyway.configure()
          .dataSource(upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())
          .locations("classpath:db/migration")
          .target(MigrationVersion.fromVersion("1"))
          .load()
          .migrate();

      long userId;
      long eventId;
      long sessionId;
      long skuId;
      long orderId;
      String passwordHash =
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
              .encode("stage3-migration-password-fixture");
      String checkinHash = sha256Hex("migration-checkin-code-fixture");
      try (Connection connection =
          DriverManager.getConnection(
              upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())) {
        userId =
            insertAndGetId(
                connection,
                "INSERT INTO users(email,password_hash,status) VALUES (?,?,'ACTIVE')",
                "upgrade-fixture@example.test",
                passwordHash);
        execute(connection, "INSERT INTO user_roles(user_id,role) VALUES (?, 'ORGANIZER')", userId);
        eventId =
            insertAndGetId(
                connection,
                "INSERT INTO events(organizer_id,title,description,status) VALUES (?,?,'legacy','PUBLISHED')",
                userId,
                "V1 preserved event");
        sessionId =
            insertAndGetId(
                connection,
                "INSERT INTO event_sessions(event_id,title,starts_at,ends_at,location) VALUES (?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),INTERVAL 1 HOUR),'hall')",
                eventId,
                "V1 preserved session");
        skuId =
            insertAndGetId(
                connection,
                "INSERT INTO ticket_skus(session_id,name,price_cents,total_stock,available_stock) VALUES (?, 'Legacy',500,3,2)",
                sessionId);
      }
      Flyway.configure()
          .dataSource(upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())
          .locations("classpath:db/migration")
          .target(MigrationVersion.fromVersion("2"))
          .load()
          .migrate();
      try (Connection connection =
          DriverManager.getConnection(
              upgradeDb.getJdbcUrl(), upgradeDb.getUsername(), upgradeDb.getPassword())) {
        orderId =
            insertAndGetId(
                connection,
                "INSERT INTO orders(order_no,user_id,idempotency_key,requested_sku_id,status,total_price_cents,expires_at,paid_at) VALUES ('0123456789abcdef0123456789abcdef',?, 'upgrade-order-1',?,'PAID',500,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE),UTC_TIMESTAMP(6))",
                userId,
                skuId);
        long itemId =
            insertAndGetId(
                connection,
                "INSERT INTO order_items(order_id,sku_id,price_cents,quantity) VALUES (?,?,500,1)",
                orderId,
                skuId);
        insertAndGetId(
            connection,
            "INSERT INTO tickets(order_item_id,owner_user_id,checkin_code_hash,status) VALUES (?,?,?,'UNUSED')",
            itemId,
            userId,
            checkinHash);
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
        assertEquals(1L, queryLong(statement, "SELECT COUNT(*) FROM users WHERE id=" + userId));
        assertEquals(
            1L,
            queryLong(
                statement,
                "SELECT COUNT(*) FROM user_roles WHERE user_id="
                    + userId
                    + " AND role='ORGANIZER'"));
        assertEquals(1L, queryLong(statement, "SELECT COUNT(*) FROM events WHERE id=" + eventId));
        assertEquals(
            1L, queryLong(statement, "SELECT COUNT(*) FROM event_sessions WHERE id=" + sessionId));
        assertEquals(
            1L, queryLong(statement, "SELECT COUNT(*) FROM ticket_skus WHERE id=" + skuId));
        assertEquals(
            1L,
            queryLong(
                statement,
                "SELECT COUNT(*) FROM orders WHERE id=" + orderId + " AND status='PAID'"));
        assertEquals(
            1L, queryLong(statement, "SELECT COUNT(*) FROM order_items WHERE order_id=" + orderId));
        assertEquals(
            1L,
            queryLong(
                statement,
                "SELECT COUNT(*) FROM tickets t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id="
                    + orderId));
        try (ResultSet result =
            statement.executeQuery("SELECT password_hash FROM users WHERE id=" + userId)) {
          assertTrue(result.next());
          assertEquals(passwordHash, result.getString(1));
        }
        try (ResultSet result =
            statement.executeQuery(
                "SELECT checkin_code_hash FROM tickets t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id="
                    + orderId)) {
          assertTrue(result.next());
          assertEquals(checkinHash, result.getString(1));
        }
        assertEquals(
            500L,
            queryLong(statement, "SELECT price_cents FROM order_items WHERE order_id=" + orderId));
        assertEquals(
            2L, queryLong(statement, "SELECT available_stock FROM ticket_skus WHERE id=" + skuId));
        assertEquals(
            1L,
            queryLong(
                statement,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1"));
        assertEquals(
            0L,
            queryLong(statement, "SELECT expiry_retry_attempts FROM orders WHERE id=" + orderId));
        assertEquals(0L, queryLong(statement, "SELECT COUNT(*) FROM outbox_events"));
      }
    }
    EVIDENCE.add(
        Map.of(
            "scenario", "fresh-and-upgrade-migrations",
            "freshVersions", List.of(1, 2, 3),
            "upgradePath", "V1 seeded with user/event/session/SKU -> V2 order/item/ticket -> V3",
            "preservedAggregateRows",
                Map.of(
                    "users",
                    1,
                    "events",
                    1,
                    "sessions",
                    1,
                    "skus",
                    1,
                    "paidOrders",
                    1,
                    "items",
                    1,
                    "tickets",
                    1),
            "result", "passed"));
  }

  @Test
  void cacheAsideUsesLiveMysqlStockInvalidatesAfterCommitAndDoesNotCacheMisses() throws Exception {
    Fixture fixture = publishedFixture("Cache live stock", 10, 800);
    cacheMetrics.reset();
    long cacheReadStartedAt = System.nanoTime();
    HttpResponse<String> first = call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    HttpResponse<String> second = call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    assertStatus(200, first);
    assertStatus(200, second);
    assertEquals(10, skuStock(json(first)));
    assertEquals(10, skuStock(json(second)));
    assertTrue(cacheMetrics.snapshot().misses() >= 1);
    assertTrue(cacheMetrics.snapshot().hits() >= 1);

    Set<String> detailKeys = redis.keys("eventflow:events:g:*:detail:" + fixture.eventId());
    assertNotNull(detailKeys);
    assertEquals(1, detailKeys.size());
    String cached = redis.opsForValue().get(detailKeys.iterator().next());
    assertNotNull(cached);
    assertFalse(cached.contains("availableStock"), "Redis stores metadata, not live stock values");
    long ttl = redis.getExpire(detailKeys.iterator().next(), TimeUnit.MILLISECONDS);
    long cacheReadElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cacheReadStartedAt);
    assertTrue(
        ttl > 0 && ttl + cacheReadElapsedMs >= 300_000 && ttl + cacheReadElapsedMs <= 315_000,
        "detail TTL includes the configured jitter after accounting for test time");

    HttpResponse<String> order = createOrder(user, fixture.skuId(), "cache-stock-key-001");
    assertStatus(201, order);
    HttpResponse<String> afterReservation =
        call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    assertStatus(200, afterReservation);
    assertEquals(9, skuStock(json(afterReservation)), "cache hit overlays current SQL stock");
    assertTrue(cacheMetrics.snapshot().hits() >= 2);

    HttpResponse<String> listBefore = call("GET", "/api/v1/events?page=0&size=100", null, null);
    assertStatus(200, listBefore);
    Fixture draft = draftFixture("After commit invalidation", 2, 100);
    long invalidationsBeforeRollback = cacheMetrics.snapshot().invalidations();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    assertThrows(
        RollbackMarker.class,
        () ->
            transaction.execute(
                status -> {
                  events.publish(draft.eventId(), organizer.id(), false);
                  throw new RollbackMarker();
                }));
    assertEquals(
        invalidationsBeforeRollback,
        cacheMetrics.snapshot().invalidations(),
        "a rolled-back publication must not advance the Redis generation");
    assertEquals(
        "DRAFT",
        jdbc.queryForObject("SELECT status FROM events WHERE id=?", String.class, draft.eventId()));

    HttpResponse<String> beforePublish = call("GET", "/api/v1/events?page=0&size=100", null, null);
    assertFalse(containsEvent(json(beforePublish), draft.eventId()));
    HttpResponse<String> published =
        call(
            "POST",
            "/api/v1/organizer/events/" + draft.eventId() + "/publish",
            organizer.token(),
            null);
    assertStatus(200, published);
    assertEquals(invalidationsBeforeRollback + 1, cacheMetrics.snapshot().invalidations());
    HttpResponse<String> afterPublish = call("GET", "/api/v1/events?page=0&size=100", null, null);
    assertTrue(containsEvent(json(afterPublish), draft.eventId()));
    assertStatus(200, call("GET", "/api/v1/events/" + draft.eventId(), null, null));

    var staleLookup = publishedEventCache.readDetail(fixture.eventId());
    assertNotNull(staleLookup.generation());
    String staleDetailKey =
        "eventflow:events:g:" + staleLookup.generation() + ":detail:" + fixture.eventId();
    redis.delete(staleDetailKey);
    Fixture casDraft = draftFixture("Cache generation CAS", 1, 101);
    HttpResponse<String> casPublished =
        call(
            "POST",
            "/api/v1/organizer/events/" + casDraft.eventId() + "/publish",
            organizer.token(),
            null);
    assertStatus(200, casPublished);
    Detail staleDetail =
        new Detail(
            fixture.eventId(),
            organizer.id(),
            "Cache live stock",
            "Stage 3 real middleware fixture",
            "PUBLISHED",
            Instant.parse(json(first).path("createdAt").asText()),
            Instant.parse(json(first).path("updatedAt").asText()),
            List.of(
                new CachedSession(
                    fixture.sessionId(),
                    fixture.eventId(),
                    "Session",
                    fixture.startsAt(),
                    fixture.endsAt(),
                    "Main Hall",
                    List.of(
                        new CachedSku(fixture.skuId(), fixture.sessionId(), "General", 800, 10)))));
    publishedEventCache.writeDetail(staleLookup, fixture.eventId(), staleDetail);
    assertFalse(Boolean.TRUE.equals(redis.hasKey(staleDetailKey)));
    assertTrue(
        cacheMetrics.snapshot().invalidations() >= invalidationsBeforeRollback + 2,
        "both committed publications advanced the namespace");

    long missingId = 9_999_999_999L;
    HttpResponse<String> missingFirst = call("GET", "/api/v1/events/" + missingId, null, null);
    HttpResponse<String> missingSecond = call("GET", "/api/v1/events/" + missingId, null, null);
    assertStatus(404, missingFirst);
    assertStatus(404, missingSecond);
    assertApiError(missingFirst);
    assertApiError(missingSecond);
    assertTrue(redis.keys("eventflow:events:g:*:detail:" + missingId).isEmpty());
    EVIDENCE.add(
        Map.of(
            "scenario",
            "cache-aside-and-after-commit-invalidation",
            "firstRead",
            "mysql-miss",
            "secondRead",
            "redis-hit",
            "cachedStockField",
            false,
            "liveStockAfterReservation",
            9,
            "rollbackInvalidation",
            false,
            "committedPublicationInvalidated",
            true,
            "negativeDetailCached",
            false,
            "result",
            "passed"));
  }

  @Test
  void redisRateLimitIsAtomicPerUserAndCacheAndOrderFallbackRecoverAfterPause() throws Exception {
    Fixture fixture = publishedFixture("Redis outage fallback", 20, 500);
    cacheMetrics.reset();
    HttpResponse<String> warm = call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    assertStatus(200, warm);

    String userQuotaKey = "eventflow:rate:orders:user:" + user.id();
    redis.opsForValue().set(userQuotaKey, "3", Duration.ofSeconds(30));
    HttpResponse<String> limitedBeforePause =
        createOrder(user, fixture.skuId(), "redis-limit-before-pause");
    assertStatus(429, limitedBeforePause);
    assertEquals("RATE_LIMIT_EXCEEDED", errorCode(limitedBeforePause));
    assertEquals(
        20,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));

    String pause = REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "2200", "ALL").getStdout();
    assertTrue(pause.contains("OK"));
    HttpResponse<String> fallbackDetail =
        call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    assertStatus(200, fallbackDetail);
    assertEquals(20, skuStock(json(fallbackDetail)));
    HttpResponse<String> failOpenOrder = createOrder(user, fixture.skuId(), "redis-failopen-001");
    assertStatus(201, failOpenOrder, "rate limiter must fail open while Redis is unreachable");
    assertTrue(cacheMetrics.snapshot().redisFailures() > 0);

    await("Redis resumes after CLIENT PAUSE", Duration.ofSeconds(8), this::redisPing);
    HttpResponse<String> recoveredFirst =
        call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    HttpResponse<String> recoveredSecond =
        call("GET", "/api/v1/events/" + fixture.eventId(), null, null);
    assertStatus(200, recoveredFirst);
    assertStatus(200, recoveredSecond);
    assertEquals(19, skuStock(json(recoveredSecond)));
    assertTrue(cacheMetrics.snapshot().hits() > 0);

    List<HttpResponse<String>> limited =
        parallelCalls(
            8,
            HTTP_WORKERS,
            index -> createOrder(secondUser, fixture.skuId(), "limit-window-" + index));
    assertEquals(3L, limited.stream().filter(response -> response.statusCode() == 201).count());
    List<HttpResponse<String>> rejected =
        limited.stream().filter(response -> response.statusCode() == 429).toList();
    assertEquals(5, rejected.size());
    for (HttpResponse<String> response : rejected) {
      assertApiError(response);
      assertEquals("RATE_LIMIT_EXCEEDED", errorCode(response));
    }
    String key = "eventflow:rate:orders:user:" + secondUser.id();
    long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
    assertTrue(ttl > 0 && ttl <= 5000);
    assertStatus(201, createOrder(admin, fixture.skuId(), "other-user-rate-001"));
    await(
        "per-user rate window expires",
        Duration.ofSeconds(8),
        () -> redis.getExpire(key, TimeUnit.MILLISECONDS) < 0);
    assertStatus(201, createOrder(secondUser, fixture.skuId(), "limit-window-after-expiry"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE requested_sku_id=? AND status='PENDING_PAYMENT' AND user_id=?",
            Long.class,
            fixture.skuId(),
            admin.id()),
        "the independent user still created exactly one order");
    EVIDENCE.add(
        Map.of(
            "scenario",
            "redis-outage-fail-open-recovery-and-rate-limit",
            "injectedFault",
            "CLIENT PAUSE 2200 ALL on real Redis",
            "catalogFallbackStatus",
            fallbackDetail.statusCode(),
            "orderDuringRedisFaultStatus",
            failOpenOrder.statusCode(),
            "sameUserLimitBeforeFaultStatus",
            limitedBeforePause.statusCode(),
            "sameUserConcurrentRequests",
            limited.size(),
            "sameUserRejected429",
            rejected.size(),
            "otherUserUnaffected",
            true,
            "ttlWindowRecovered",
            true,
            "result",
            "passed"));
  }

  @Test
  void outboxWritesRollbackWithOrdersAndMandatoryReturnKeepsUnroutableEventPending()
      throws Exception {
    Fixture fixture = publishedFixture("Outbox atomicity", 5, 600);
    jdbc.execute(
        "CREATE TRIGGER "
            + OUTBOX_TRIGGER
            + " BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
    try {
      HttpResponse<String> failed = createOrder(user, fixture.skuId(), "outbox-trigger-key-1");
      assertStatus(500, failed);
      assertApiError(failed);
      assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
      assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class));
      assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class));
      assertEquals(
          5,
          jdbc.queryForObject(
              "SELECT available_stock FROM ticket_skus WHERE id=?",
              Integer.class,
              fixture.skuId()));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS " + OUTBOX_TRIGGER);
    }

    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    assertThrows(
        RollbackMarker.class,
        () ->
            transaction.execute(
                status -> {
                  var created = orders.create(user.id(), fixture.skuId(), "outbox-tx-rollback-1");
                  assertTrue(created.created());
                  assertEquals(
                      1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class));
                  assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
                  throw new RollbackMarker();
                }));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class));
    assertEquals(
        5,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));

    HttpResponse<String> created = createOrder(user, fixture.skuId(), "unroutable-event-001");
    assertStatus(201, created);
    String eventId = outboxEventId("ORDER_CREATED");
    Binding binding =
        new Binding(
            MessagingNames.ORDER_EXPIRY_DELAY_QUEUE,
            Binding.DestinationType.QUEUE,
            MessagingNames.EXCHANGE,
            MessagingNames.ORDER_CREATED_ROUTE,
            Map.of());
    rabbitAdmin.removeBinding(binding);
    try {
      assertDoesNotThrow(() -> outboxPublisher.publishBatchOnce());
      assertEquals("PENDING", outboxStatus(eventId));
      assertEquals(
          "UNROUTABLE",
          jdbc.queryForObject(
              "SELECT last_error FROM outbox_events WHERE event_id=?", String.class, eventId));
      assertTrue(outboxAttempts(eventId) >= 1);
    } finally {
      rabbitAdmin.declareBinding(binding);
    }
    EVIDENCE.add(
        Map.of(
            "scenario",
            "outbox-transaction-and-mandatory-return",
            "triggeredOutboxInsertFailureRolledBackOrderAndStock",
            true,
            "outerTransactionRollbackRemovedOutboxOrderItemAndReservation",
            true,
            "unroutableEventStatus",
            outboxStatus(eventId),
            "unroutableReason",
            "UNROUTABLE",
            "result",
            "passed"));
  }

  @Test
  void paidAndClosedOutboxFailuresRollbackTicketsAndStockAndReplaysCreateNoEvents()
      throws Exception {
    Fixture fixture = publishedFixture("Terminal Outbox rollback", 2, 600);
    long paidId = orders.create(user.id(), fixture.skuId(), "atomic-pay-key-001").order().id();
    long closedId =
        orders.create(secondUser.id(), fixture.skuId(), "atomic-close-key-1").order().id();
    jdbc.execute(
        "CREATE TRIGGER "
            + OUTBOX_TRIGGER
            + " BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
    try {
      assertStatus(500, call("POST", "/api/v1/orders/" + paidId + "/pay", user.token(), null));
      assertStatus(
          500, call("POST", "/api/v1/orders/" + closedId + "/cancel", secondUser.token(), null));
      assertEquals(
          2L,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM orders WHERE status='PENDING_PAYMENT'", Long.class));
      assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class));
      assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT available_stock FROM ticket_skus WHERE id=?",
              Integer.class,
              fixture.skuId()));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS " + OUTBOX_TRIGGER);
    }
    assertStatus(200, call("POST", "/api/v1/orders/" + paidId + "/pay", user.token(), null));
    assertStatus(200, call("POST", "/api/v1/orders/" + paidId + "/pay", user.token(), null));
    assertStatus(
        200, call("POST", "/api/v1/orders/" + closedId + "/cancel", secondUser.token(), null));
    assertStatus(
        200, call("POST", "/api/v1/orders/" + closedId + "/cancel", secondUser.token(), null));
    assertFalse(orders.create(user.id(), fixture.skuId(), "atomic-pay-key-001").created());
    assertFalse(orders.create(secondUser.id(), fixture.skuId(), "atomic-close-key-1").created());
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type='ORDER_CREATED'", Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type='ORDER_PAID'", Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type='ORDER_CLOSED'", Long.class));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?", Integer.class, fixture.skuId()));
    EVIDENCE.add(
        Map.of(
            "scenario",
            "terminal-outbox-rollback-and-idempotent-transitions",
            "paidInsertFailure",
            "rolled back status and ticket",
            "closedInsertFailure",
            "rolled back status and inventory release",
            "eventCountsAfterReplays",
            Map.of("ORDER_CREATED", 2, "ORDER_PAID", 1, "ORDER_CLOSED", 1),
            "finalTicketCount",
            1,
            "finalAvailableStock",
            1,
            "result",
            "passed"));
  }

  @Test
  void publisherRecoversPendingOutboxAfterRabbitApplicationIsUnavailable() throws Exception {
    Fixture fixture = publishedFixture("Rabbit restart", 3, 700);
    String eventId;
    rabbitStopApp();
    try {
      HttpResponse<String> created = createOrder(user, fixture.skuId(), "rabbit-down-key-001");
      assertStatus(201, created);
      eventId = outboxEventId("ORDER_CREATED");
      assertDoesNotThrow(() -> outboxPublisher.publishBatchOnce());
      assertEquals("PENDING", outboxStatus(eventId));
      assertTrue(outboxAttempts(eventId) >= 1);
      assertNotEquals("SENT", outboxStatus(eventId));
      EVIDENCE.add(
          Map.of(
              "scenario",
              "business-order-during-rabbit-outage",
              "createHttpStatus",
              created.statusCode(),
              "outboxStatusWhileRabbitStopped",
              outboxStatus(eventId),
              "result",
              "passed"));
    } finally {
      rabbitStartApp();
    }
    LocalDateTime nextAttempt =
        jdbc.queryForObject(
            "SELECT next_attempt_at FROM outbox_events WHERE event_id=?",
            LocalDateTime.class,
            eventId);
    clock.set(nextAttempt.toInstant(ZoneOffset.UTC).plusMillis(10));
    await(
        "RabbitMQ accepts AMQP after restart", Duration.ofSeconds(20), this::rabbitAmqpResponding);
    assertEquals(1, outboxPublisher.publishBatchOnce());
    assertEquals("SENT", outboxStatus(eventId));
    assertTrue(queueDepth(MessagingNames.ORDER_EXPIRY_DELAY_QUEUE) >= 1);
    EVIDENCE.add(
        Map.of(
            "scenario", "outbox-rabbit-down-recovery",
            "whileRabbitApplicationStopped", "outbox stayed PENDING and attempts increased",
            "afterRecovery", outboxStatus(eventId),
            "publishedToDelayQueue", true,
            "result", "passed"));
  }

  @Test
  void publisherConfirmTimeoutIsNotMistakenForSuccessfulDelivery() throws Exception {
    Fixture fixture = publishedFixture("Rabbit confirm timeout", 2, 800);
    HttpResponse<String> created = createOrder(user, fixture.skuId(), "rabbit-timeout-key-1");
    assertStatus(201, created);
    String eventId = outboxEventId("ORDER_CREATED");
    assertNotNull(rabbitAdmin.getQueueInfo(MessagingNames.ORDER_EXPIRY_DELAY_QUEUE));
    assertDoesNotThrow(
        () ->
            rabbit.execute(
                channel -> {
                  assertTrue(
                      channel.isOpen(),
                      "the publisher channel is connected before fault injection");
                  return null;
                }));
    String rabbitId = RABBIT.getContainerId();
    RABBIT.getDockerClient().pauseContainerCmd(rabbitId).exec();
    try {
      assertDoesNotThrow(() -> outboxPublisher.publishBatchOnce());
      assertEquals("PENDING", outboxStatus(eventId));
      assertEquals(
          "CONFIRM_TIMEOUT",
          jdbc.queryForObject(
              "SELECT last_error FROM outbox_events WHERE event_id=?", String.class, eventId));
    } finally {
      RABBIT.getDockerClient().unpauseContainerCmd(rabbitId).exec();
    }
    await(
        "RabbitMQ resumes after a paused-container confirm timeout",
        Duration.ofSeconds(20),
        this::rabbitAmqpResponding);
    assertNotEquals("SENT", outboxStatus(eventId));
    EVIDENCE.add(
        Map.of(
            "scenario", "publisher-confirm-timeout",
            "injectedFault", "paused real RabbitMQ container after opening the broker channel",
            "outboxStatus", outboxStatus(eventId),
            "lastError", "CONFIRM_TIMEOUT",
            "result", "passed"));
  }

  @Test
  void paidNotificationsAreDurableIdempotentOwnerScopedAndSurviveListenerRestart()
      throws Exception {
    Fixture fixture = publishedFixture("Notification flow", 4, 500);
    HttpResponse<String> created = createOrder(user, fixture.skuId(), "notification-paid-01");
    long orderId = json(created).path("id").asLong();
    HttpResponse<String> paid =
        call("POST", "/api/v1/orders/" + orderId + "/pay", user.token(), null);
    assertStatus(200, paid);
    String eventId = outboxEventId("ORDER_PAID");
    assertTrue(outboxPublisher.publishBatchOnce() >= 1);
    await(
        "paid event creates durable notification",
        Duration.ofSeconds(15),
        () -> notificationCount(user.id()) == 1);
    assertEquals(1L, consumedEventCount(eventId));

    stopListener(NOTIFICATION_LISTENER);
    rabbit.convertAndSend(MessagingNames.EXCHANGE, MessagingNames.ORDER_PAID_ROUTE, eventId);
    await(
        "duplicate paid message is durably queued while its consumer is stopped",
        Duration.ofSeconds(5),
        () -> queueDepth(MessagingNames.NOTIFICATION_QUEUE) == 1);
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_notifications WHERE event_id=?", Long.class, eventId));

    Fixture secondFixture = publishedFixture("Listener restart", 2, 300);
    HttpResponse<String> secondOrder =
        createOrder(secondUser, secondFixture.skuId(), "notify-restart-01");
    long secondOrderId = json(secondOrder).path("id").asLong();
    assertStatus(
        200, call("POST", "/api/v1/orders/" + secondOrderId + "/pay", secondUser.token(), null));
    String secondEventId = outboxEventIdForOrder("ORDER_PAID", secondOrderId);
    rabbit.convertAndSend(MessagingNames.EXCHANGE, MessagingNames.ORDER_PAID_ROUTE, secondEventId);
    await(
        "duplicate and new paid messages remain durable while notification listener is stopped",
        Duration.ofSeconds(5),
        () -> queueDepth(MessagingNames.NOTIFICATION_QUEUE) == 2);
    assertEquals(0L, notificationCount(secondUser.id()));
    startListener(NOTIFICATION_LISTENER);
    await(
        "listener restart drains duplicate and new messages in FIFO order",
        Duration.ofSeconds(10),
        () ->
            queueDepth(MessagingNames.NOTIFICATION_QUEUE) == 0
                && notificationCount(secondUser.id()) == 1);
    assertEquals(1L, notificationCount(user.id()));
    assertEquals(1L, consumedEventCount(secondEventId));

    HttpResponse<String> own =
        call("GET", "/api/v1/me/notifications?page=0&size=20", user.token(), null);
    HttpResponse<String> other =
        call("GET", "/api/v1/me/notifications?page=0&size=20", secondUser.token(), null);
    HttpResponse<String> adminOwn =
        call("GET", "/api/v1/me/notifications?page=0&size=20", admin.token(), null);
    HttpResponse<String> organizerDenied =
        call("GET", "/api/v1/me/notifications?page=0&size=20", organizer.token(), null);
    HttpResponse<String> anonymous =
        call("GET", "/api/v1/me/notifications?page=0&size=20", null, null);
    assertStatus(200, own);
    assertStatus(200, other);
    assertStatus(200, adminOwn);
    assertEquals(1, json(own).path("total").asInt());
    assertEquals(1, json(other).path("total").asInt());
    assertEquals(0, json(adminOwn).path("total").asInt());
    assertEquals(orderId, json(own).path("items").path(0).path("orderId").asLong());
    assertEquals(403, organizerDenied.statusCode());
    assertApiError(organizerDenied);
    assertEquals(401, anonymous.statusCode());
    assertApiError(anonymous);
    EVIDENCE.add(
        Map.of(
            "scenario",
            "notification-outbox-idempotency-owner-scope-and-restart",
            "duplicateDeliveryNotificationRows",
            1,
            "listenerStoppedMessageRetained",
            true,
            "listenerRestartDelivered",
            true,
            "otherUserOnlyOwnNotifications",
            true,
            "result",
            "passed"));
  }

  @Test
  void poisonAndFailedNotificationTransactionsReachDeadLetterAndCanBeRedriven() throws Exception {
    rabbit.convertAndSend(
        MessagingNames.EXCHANGE, MessagingNames.ORDER_PAID_ROUTE, "not-a-valid-event-id");
    await(
        "malformed message reaches durable DLQ",
        Duration.ofSeconds(10),
        () -> queueDepth(MessagingNames.DEAD_LETTER_QUEUE) == 1);
    Message poison = rabbit.receive(MessagingNames.DEAD_LETTER_QUEUE, 1000);
    assertNotNull(poison);
    assertEquals("INVALID_EVENT_ID", new String(poison.getBody(), StandardCharsets.UTF_8));
    assertEquals(
        "INVALID_EVENT_ID",
        poison.getMessageProperties().getHeaders().get(MessagingNames.ERROR_CODE_HEADER));
    assertEquals(
        "0",
        String.valueOf(
            poison.getMessageProperties().getHeaders().get(MessagingNames.RETRY_COUNT_HEADER)));

    Fixture fixture = publishedFixture("Notification retry", 2, 300);
    HttpResponse<String> created = createOrder(user, fixture.skuId(), "notification-retry-01");
    long orderId = json(created).path("id").asLong();
    assertStatus(200, call("POST", "/api/v1/orders/" + orderId + "/pay", user.token(), null));
    String eventId = outboxEventId("ORDER_PAID");
    jdbc.execute(
        "CREATE TRIGGER "
            + NOTIFICATION_TRIGGER
            + " BEFORE INSERT ON user_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected notification persistence failure'");
    try {
      rabbit.convertAndSend(MessagingNames.EXCHANGE, MessagingNames.ORDER_PAID_ROUTE, eventId);
      await(
          "failed notification transaction reaches DLQ after bounded retries",
          Duration.ofSeconds(15),
          () -> queueDepth(MessagingNames.DEAD_LETTER_QUEUE) >= 1);
      assertEquals(
          0L,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM user_notifications WHERE event_id=?", Long.class, eventId));
      assertEquals(0L, consumedEventCount(eventId));
      Message failedDelivery = rabbit.receive(MessagingNames.DEAD_LETTER_QUEUE, 1000);
      assertNotNull(failedDelivery);
      assertEquals(eventId, new String(failedDelivery.getBody(), StandardCharsets.UTF_8));
      assertEquals(
          "2",
          String.valueOf(
              failedDelivery
                  .getMessageProperties()
                  .getHeaders()
                  .get(MessagingNames.RETRY_COUNT_HEADER)));
      assertNotNull(
          failedDelivery.getMessageProperties().getHeaders().get(MessagingNames.ERROR_CODE_HEADER));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS " + NOTIFICATION_TRIGGER);
    }
    rabbit.convertAndSend(MessagingNames.EXCHANGE, MessagingNames.ORDER_PAID_ROUTE, eventId);
    await(
        "redriven notification persists once",
        Duration.ofSeconds(10),
        () -> notificationCount(user.id()) == 1);
    assertEquals(1L, consumedEventCount(eventId));
    EVIDENCE.add(
        Map.of(
            "scenario", "poison-dlq-bounded-consumer-retry-and-redrive",
            "malformedMessage", "dead-lettered",
            "injectedPersistenceFailure", "rolled back notification and consumed marker",
            "retryLimit", 2,
            "manualRedriveAfterRepair", "one notification persisted",
            "result", "passed"));
  }

  @Test
  void rabbitExpiryPathHandlesEarlyDueLateDuplicateOutOfOrderAndStateRaces() throws Exception {
    Instant now = clock.instant();
    Fixture earlyFixture =
        publishedFixtureAt("Early TTL expiry", now.plusSeconds(6), now.plusSeconds(90), 3, 600);
    HttpResponse<String> earlyOrder =
        createOrder(user, earlyFixture.skuId(), "early-rabbit-order-1");
    assertStatus(201, earlyOrder);
    long earlyId = json(earlyOrder).path("id").asLong();
    Instant earlyExpiry = Instant.parse(json(earlyOrder).path("expiresAt").asText());
    String earlyEventId = outboxEventIdForOrder("ORDER_CREATED", earlyId);

    rabbit.convertAndSend(
        MessagingNames.EXCHANGE, MessagingNames.ORDER_EXPIRED_ROUTE, earlyEventId);
    await(
        "early Rabbit delivery is rescheduled into the durable TTL queue",
        Duration.ofSeconds(10),
        () -> queueDepth(MessagingNames.ORDER_EXPIRY_DELAY_QUEUE) >= 1);
    assertEquals(
        "PENDING_PAYMENT",
        jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, earlyId));
    assertEquals(
        0L, consumedEventCount(earlyEventId), "early events remain eligible for redelivery");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            earlyFixture.skuId()));

    clock.set(earlyExpiry);
    await(
        "the real Rabbit TTL/DLX route closes the order at its database expiry",
        Duration.ofSeconds(20),
        () ->
            "CLOSED"
                .equals(
                    jdbc.queryForObject(
                        "SELECT status FROM orders WHERE id=?", String.class, earlyId)));
    assertEquals(1L, consumedEventCount(earlyEventId));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
            Long.class,
            earlyId));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            earlyFixture.skuId()));
    assertEquals(
        "ALREADY_COMPLETED",
        expiryMessages.handle(earlyEventId).outcome().name(),
        "a duplicate event is harmless after durable consumer deduplication");
    stopListener(EXPIRY_LISTENER);
    rabbit.convertAndSend(
        MessagingNames.EXCHANGE, MessagingNames.ORDER_EXPIRED_ROUTE, earlyEventId);
    await(
        "duplicate expiry message is durably queued while its consumer is stopped",
        Duration.ofSeconds(5),
        () -> queueDepth(MessagingNames.ORDER_EXPIRY_QUEUE) == 1);
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
            Long.class,
            earlyId));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            earlyFixture.skuId()));

    Fixture paidFixture = publishedFixture("Expiry after payment", 2, 450);
    HttpResponse<String> paidOrder =
        createOrder(secondUser, paidFixture.skuId(), "out-of-order-paid-1");
    long paidId = json(paidOrder).path("id").asLong();
    assertStatus(200, call("POST", "/api/v1/orders/" + paidId + "/pay", secondUser.token(), null));
    Instant paidExpiry = Instant.parse(json(paidOrder).path("expiresAt").asText());
    String paidCreatedEvent = outboxEventIdForOrder("ORDER_CREATED", paidId);
    clock.set(paidExpiry);
    rabbit.convertAndSend(
        MessagingNames.EXCHANGE, MessagingNames.ORDER_EXPIRED_ROUTE, paidCreatedEvent);
    await(
        "duplicate and out-of-order expiry messages remain durable while the consumer is stopped",
        Duration.ofSeconds(5),
        () -> queueDepth(MessagingNames.ORDER_EXPIRY_QUEUE) == 2);
    startListener(EXPIRY_LISTENER);
    await(
        "listener restart drains the duplicate before the PAID out-of-order event",
        Duration.ofSeconds(10),
        () ->
            queueDepth(MessagingNames.ORDER_EXPIRY_QUEUE) == 0
                && consumedEventCount(paidCreatedEvent) == 1);
    assertEquals(
        "PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, paidId));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
            Long.class,
            paidId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            paidFixture.skuId()));

    Fixture lateFixture =
        publishedFixtureAt(
            "Late Rabbit expiry",
            clock.instant().plusSeconds(30),
            clock.instant().plusSeconds(3600),
            1,
            700);
    HttpResponse<String> lateOrder = createOrder(admin, lateFixture.skuId(), "late-rabbit-order-1");
    long lateId = json(lateOrder).path("id").asLong();
    String lateEventId = outboxEventIdForOrder("ORDER_CREATED", lateId);
    Instant lateExpiry = Instant.parse(json(lateOrder).path("expiresAt").asText());
    clock.set(lateExpiry.plusSeconds(1));
    assertTrue(outboxPublisher.publishBatchOnce() >= 1);
    assertEquals("SENT", outboxStatus(lateEventId));
    await(
        "late Rabbit delivery closes expired reservation",
        Duration.ofSeconds(10),
        () ->
            "CLOSED"
                .equals(
                    jdbc.queryForObject(
                        "SELECT status FROM orders WHERE id=?", String.class, lateId)));
    assertEquals(1L, consumedEventCount(lateEventId));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
            Long.class,
            lateId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            lateFixture.skuId()));

    Fixture cancelRaceFixture = publishedFixture("Rabbit cancel race", 1, 500);
    HttpResponse<String> cancelPending =
        createOrder(user, cancelRaceFixture.skuId(), "rabbit-cancel-race1");
    long cancelId = json(cancelPending).path("id").asLong();
    String cancelEventId = outboxEventIdForOrder("ORDER_CREATED", cancelId);
    clock.set(Instant.parse(json(cancelPending).path("expiresAt").asText()));
    List<Integer> raceResults =
        parallelCallables(
            2,
            2,
            index -> {
              if (index == 0) {
                rabbit.convertAndSend(
                    MessagingNames.EXCHANGE, MessagingNames.ORDER_EXPIRED_ROUTE, cancelEventId);
                return 0;
              }
              return call("POST", "/api/v1/orders/" + cancelId + "/cancel", user.token(), null)
                  .statusCode();
            });
    assertTrue(raceResults.contains(200));
    await(
        "Rabbit expiry and HTTP cancellation settle to CLOSED",
        Duration.ofSeconds(10),
        () ->
            "CLOSED"
                .equals(
                    jdbc.queryForObject(
                        "SELECT status FROM orders WHERE id=?", String.class, cancelId)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT available_stock FROM ticket_skus WHERE id=?",
            Integer.class,
            cancelRaceFixture.skuId()));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
            Long.class,
            cancelId));

    Fixture payRaceFixture = publishedFixture("Rabbit pay race", 1, 500);
    HttpResponse<String> payPending =
        createOrder(secondUser, payRaceFixture.skuId(), "rabbit-pay-race-001");
    long payId = json(payPending).path("id").asLong();
    String payEventId = outboxEventIdForOrder("ORDER_CREATED", payId);
    List<Integer> payResults =
        parallelCallables(
            2,
            2,
            index -> {
              if (index == 0) {
                rabbit.convertAndSend(
                    MessagingNames.EXCHANGE, MessagingNames.ORDER_EXPIRED_ROUTE, payEventId);
                return 0;
              }
              return call("POST", "/api/v1/orders/" + payId + "/pay", secondUser.token(), null)
                  .statusCode();
            });
    assertTrue(payResults.contains(200));
    assertEquals(
        "PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, payId));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tickets t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id=?",
            Long.class,
            payId));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
            Long.class,
            payId));
    EVIDENCE.add(
        Map.of(
            "scenario", "rabbit-early-due-late-duplicate-out-of-order-and-races",
            "early", "rescheduled without consuming or releasing stock",
            "dueViaTtlDlx", "closed once and restored stock once",
            "late", "publisher sent directly to live expiry route and closed",
            "duplicate", "deduplicated with no second release or event",
            "outOfOrderAfterPayment", "PAID remained terminal",
            "cancelRace", "CLOSED, one release and one close event",
            "payRace", "PAID with one ticket and no close event",
            "result", "passed"));
  }

  @Test
  void scannerContinuesPastOneHundredPoisonOrdersWhileRabbitIsStoppedAndBacksOffFailures()
      throws Exception {
    Fixture fixture = publishedFixture("Scanner fairness", 101, 100);
    List<Long> orderIds = new ArrayList<>(101);
    for (int index = 0; index < 101; index++) {
      var result =
          orders.create(user.id(), fixture.skuId(), String.format("scan-poison-%05d", index));
      assertTrue(result.created());
      orderIds.add(result.order().id());
    }
    jdbc.batchUpdate(
        "DELETE FROM order_items WHERE order_id=?",
        orderIds.subList(0, 100).stream().map(id -> new Object[] {id}).toList());
    Instant expiresAt =
        jdbc.queryForObject(
                "SELECT expires_at FROM orders WHERE id=?", LocalDateTime.class, orderIds.get(0))
            .toInstant(ZoneOffset.UTC);
    clock.set(expiresAt);
    rabbitStopApp();
    try {
      assertEquals(
          0,
          expiryScanner.scanOnce(),
          "the first full batch contains only malformed poisoned orders");
      assertEquals(
          100L,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM orders WHERE expiry_retry_attempts=1 AND status='PENDING_PAYMENT'",
              Long.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT expiry_retry_attempts FROM orders WHERE id=?",
              Integer.class,
              orderIds.get(100)));
      LocalDateTime retryAt =
          jdbc.queryForObject(
              "SELECT expiry_retry_at FROM orders WHERE id=?",
              LocalDateTime.class,
              orderIds.get(0));
      assertTrue(retryAt.isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));

      assertEquals(
          1,
          expiryScanner.scanOnce(),
          "the healthy 101st order must not be starved by poison rows");
      assertEquals(
          "CLOSED",
          jdbc.queryForObject(
              "SELECT status FROM orders WHERE id=?", String.class, orderIds.get(100)));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT available_stock FROM ticket_skus WHERE id=?",
              Integer.class,
              fixture.skuId()));
      assertEquals(0, expiryScanner.scanOnce());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT expiry_retry_attempts FROM orders WHERE id=?",
              Integer.class,
              orderIds.get(0)));

      clock.set(retryAt.toInstant(ZoneOffset.UTC));
      assertEquals(0, expiryScanner.scanOnce());
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT expiry_retry_attempts FROM orders WHERE id=?",
              Integer.class,
              orderIds.get(0)));
      assertTrue(
          jdbc.queryForObject(
                  "SELECT COUNT(*) FROM orders WHERE status='PENDING_PAYMENT' AND expiry_retry_attempts=2",
                  Long.class)
              >= 1);
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT available_stock FROM ticket_skus WHERE id=?",
              Integer.class,
              fixture.skuId()));
    } finally {
      rabbitStartApp();
    }
    EVIDENCE.add(
        Map.of(
            "scenario",
            "mysql-scanner-101-order-poison-starvation-and-backoff",
            "rabbitApplication",
            "stopped during scan",
            "poisonRowsInInitialBatch",
            100,
            "healthy101stClosedOnNextScan",
            true,
            "poisonAttemptsBeforeBackoff",
            1,
            "poisonAttemptsAfterDueRetry",
            2,
            "stockReleasedByHealthyOrder",
            1,
            "result",
            "passed"));
  }

  @Test
  void outboxLeasesExcludeConcurrentClaimersRecoverAfterExpiryAndPublishInParallel()
      throws Exception {
    Fixture fixture = publishedFixture("Outbox lease recovery", 12, 500);
    HttpResponse<String> firstCreated = createOrder(user, fixture.skuId(), "lease-recovery-001");
    assertStatus(201, firstCreated);
    long firstOrderId = json(firstCreated).path("id").asLong();
    String eventId = outboxEventIdForOrder("ORDER_CREATED", firstOrderId);

    AtomicReference<OutboxClaim> firstOwner = new AtomicReference<>();
    List<Integer> claimResults =
        parallelCallables(
            12,
            12,
            ignored -> {
              OutboxClaim claim = outboxClaims.claimOne();
              if (claim != null) {
                firstOwner.set(claim);
                return 1;
              }
              return 0;
            });
    assertEquals(1, claimResults.stream().mapToInt(Integer::intValue).sum());
    OutboxClaim staleOwner = firstOwner.get();
    assertNotNull(staleOwner);
    assertEquals(eventId, staleOwner.eventId());

    clock.set(clock.instant().plusSeconds(6));
    OutboxClaim recoveredOwner = outboxClaims.claimOne();
    assertNotNull(recoveredOwner, "an expired CLAIMED lease must be reclaimable");
    assertEquals(eventId, recoveredOwner.eventId());
    assertNotEquals(staleOwner.leaseOwner(), recoveredOwner.leaseOwner());
    assertFalse(
        outboxClaims.markSent(staleOwner), "the previous owner cannot mark a reclaimed event sent");
    assertFalse(
        outboxClaims.markFailure(staleOwner, "STALE_OWNER"),
        "the previous owner cannot mutate the replacement lease");
    assertTrue(outboxClaims.markFailure(recoveredOwner, "INJECTED_FAILURE"));
    assertEquals("PENDING", outboxStatus(eventId));

    LocalDateTime retryAt =
        jdbc.queryForObject(
            "SELECT next_attempt_at FROM outbox_events WHERE event_id=?",
            LocalDateTime.class,
            eventId);
    clock.set(retryAt.toInstant(ZoneOffset.UTC).plusMillis(10));
    assertEquals(1, outboxPublisher.publishBatchOnce());
    assertEquals("SENT", outboxStatus(eventId));
    assertEquals(3, outboxAttempts(eventId));

    List<Long> parallelOrderIds = new ArrayList<>();
    HttpResponse<String> firstParallelOrder =
        createOrder(user, fixture.skuId(), "parallel-publisher-user-1");
    HttpResponse<String> secondParallelOrder =
        createOrder(secondUser, fixture.skuId(), "parallel-publisher-user-1");
    HttpResponse<String> thirdParallelOrder =
        createOrder(admin, fixture.skuId(), "parallel-publisher-admin-1");
    assertStatus(201, firstParallelOrder);
    assertStatus(201, secondParallelOrder);
    assertStatus(201, thirdParallelOrder);
    parallelOrderIds.add(json(firstParallelOrder).path("id").asLong());
    parallelOrderIds.add(json(secondParallelOrder).path("id").asLong());
    parallelOrderIds.add(json(thirdParallelOrder).path("id").asLong());
    List<String> parallelEventIds =
        parallelOrderIds.stream().map(id -> outboxEventIdForOrder("ORDER_CREATED", id)).toList();
    List<Integer> publishedCounts =
        parallelCallables(2, 2, ignored -> outboxPublisher.publishBatchOnce());
    assertEquals(3, publishedCounts.stream().mapToInt(Integer::intValue).sum());
    for (String parallelEventId : parallelEventIds) {
      assertEquals("SENT", outboxStatus(parallelEventId));
      assertEquals(1, outboxAttempts(parallelEventId));
    }
    EVIDENCE.add(
        Map.of(
            "scenario",
            "outbox-concurrent-claims-lease-expiry-and-parallel-publishers",
            "simultaneousClaimers",
            claimResults.size(),
            "oneInitialLeaseOwner",
            true,
            "expiredLeaseReclaimed",
            true,
            "staleOwnerMutationsRejected",
            true,
            "recoveredEventPublished",
            outboxStatus(eventId),
            "parallelPublishers",
            2,
            "parallelEventsSentOnce",
            parallelEventIds.size(),
            "result",
            "passed"));
  }

  private void verifyFreshV1V2V3SchemaAndNoSeedUsers() {
    OBSERVED_MYSQL_VERSION = mysqlVersion();
    assertTrue(OBSERVED_MYSQL_VERSION.startsWith("8.4"));
    assertEquals(1L, versionCount("1"));
    assertEquals(1L, versionCount("2"));
    assertEquals(1L, versionCount("3"));
    assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
    assertEquals(
        12L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('users','user_roles','events','event_sessions','ticket_skus','orders','order_items','tickets','checkin_records','outbox_events','consumed_events','user_notifications')",
            Long.class));
  }

  private String mysqlVersion() {
    return jdbc.queryForObject("SELECT VERSION()", String.class);
  }

  private long versionCount(String version) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM flyway_schema_history WHERE version=? AND success=1",
        Long.class,
        version);
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
    String email = "stage3-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
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

  private long createEvent(Account eventOrganizer, String title) throws Exception {
    HttpResponse<String> response =
        call(
            "POST",
            "/api/v1/organizer/events",
            eventOrganizer.token(),
            Map.of("title", title, "description", "Stage 3 real middleware fixture"));
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
    return call(
        "POST",
        "/api/v1/orders",
        account.token(),
        Map.of("skuId", skuId, "idempotencyKey", idempotencyKey));
  }

  private List<HttpResponse<String>> parallelCalls(
      int count, int workers, ThrowingIndexedFunction<HttpResponse<String>> operation)
      throws Exception {
    int parallelism = Math.min(count, workers);
    assertTrue(parallelism > 0);
    CountDownLatch ready = new CountDownLatch(parallelism);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger started = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(parallelism);
    List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>(count);
    try {
      for (int index = 0; index < count; index++) {
        final int value = index;
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  if (started.getAndIncrement() < parallelism) {
                    ready.countDown();
                  }
                  try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                      throw new AssertionError("Concurrent HTTP barrier timed out");
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
    int parallelism = Math.min(count, workers);
    assertTrue(parallelism > 0);
    CountDownLatch ready = new CountDownLatch(parallelism);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger started = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(parallelism);
    List<CompletableFuture<Integer>> futures = new ArrayList<>(count);
    try {
      for (int index = 0; index < count; index++) {
        final int value = index;
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  if (started.getAndIncrement() < parallelism) {
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

  private static void assertStatus(int expected, HttpResponse<String> response) {
    assertStatus(expected, response, "");
  }

  private static void assertStatus(
      int expected, HttpResponse<String> response, String explanation) {
    assertEquals(
        expected,
        response.statusCode(),
        () ->
            "Expected HTTP "
                + expected
                + ", received "
                + response.statusCode()
                + " content-type="
                + response.headers().firstValue("content-type").orElse("<missing>")
                + (explanation.isBlank() ? "" : " (" + explanation + ")"));
  }

  private static void assertApiError(HttpResponse<String> response) {
    JsonNode error = json(response);
    assertTrue(error.isObject(), "Errors must be JSON objects");
    for (String field : List.of("code", "message", "requestId", "timestamp")) {
      assertTrue(error.hasNonNull(field), "Error must include " + field);
      assertFalse(error.path(field).asText().isBlank(), field + " must not be blank");
    }
  }

  private static String errorCode(HttpResponse<String> response) {
    assertApiError(response);
    return json(response).path("code").asText();
  }

  private String outboxEventId(String eventType) {
    return jdbc.queryForObject(
        "SELECT event_id FROM outbox_events WHERE event_type=? ORDER BY created_at DESC LIMIT 1",
        String.class,
        eventType);
  }

  private String outboxEventIdForOrder(String eventType, long orderId) {
    return jdbc.queryForObject(
        "SELECT event_id FROM outbox_events WHERE event_type=? AND aggregate_id=? ORDER BY created_at DESC LIMIT 1",
        String.class,
        eventType,
        orderId);
  }

  private String outboxStatus(String eventId) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox_events WHERE event_id=?", String.class, eventId);
  }

  private int outboxAttempts(String eventId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM outbox_events WHERE event_id=?", Integer.class, eventId);
  }

  private long notificationCount(long userId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_notifications WHERE user_id=?", Long.class, userId);
  }

  private long consumedEventCount(String eventId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM consumed_events WHERE event_id=?", Long.class, eventId);
  }

  private long queueDepth(String queue) {
    var info = rabbitAdmin.getQueueInfo(queue);
    return info == null ? 0 : info.getMessageCount();
  }

  private void stopListener(String listenerId) {
    var container = listenerRegistry.getListenerContainer(listenerId);
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  private void startListener(String listenerId) {
    var container = listenerRegistry.getListenerContainer(listenerId);
    assertNotNull(container, "expected Rabbit listener " + listenerId);
    if (!container.isRunning()) {
      container.start();
    }
  }

  private boolean redisPing() {
    try {
      return "PONG".equals(REDIS.execInContainer("redis-cli", "PING").getStdout().trim());
    } catch (Exception unavailable) {
      return false;
    }
  }

  private boolean rabbitAmqpResponding() {
    try {
      return rabbitAdmin.getQueueInfo(MessagingNames.ORDER_EXPIRY_QUEUE) != null;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void rabbitStopApp() throws Exception {
    var result = RABBIT.execInContainer("su-exec", "rabbitmq", "rabbitmqctl", "stop_app");
    assertEquals(0, result.getExitCode(), result.getStderr());
  }

  private void rabbitStartApp() throws Exception {
    var result = RABBIT.execInContainer("su-exec", "rabbitmq", "rabbitmqctl", "start_app");
    assertEquals(0, result.getExitCode(), result.getStderr());
  }

  private void await(String description, Duration timeout, ThrowingBooleanSupplier condition)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    assertTrue(condition.getAsBoolean(), description);
  }

  private static int skuStock(JsonNode event) {
    return event.path("sessions").path(0).path("ticketSkus").path(0).path("availableStock").asInt();
  }

  private static boolean containsEvent(JsonNode page, long eventId) {
    for (JsonNode item : page.path("items")) {
      if (item.path("id").asLong() == eventId) {
        return true;
      }
    }
    return false;
  }

  private static long insertAndGetId(Connection connection, String sql, Object... arguments)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      assertEquals(1, statement.executeUpdate());
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertTrue(keys.next());
        return keys.getLong(1);
      }
    }
  }

  private static void execute(Connection connection, String sql, Object... arguments)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      statement.executeUpdate();
    }
  }

  private static long queryLong(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static MySQLContainer<?> mysqlContainer(String database) {
    return new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName(database)
        // Fault-injection triggers are allowed only in this disposable test database.
        .withCommand(
            "mysqld", "--innodb-redo-log-capacity=128M", "--log-bin-trust-function-creators=ON")
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
    String content = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content + "." + base64Url(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String sha256Hex(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static String randomHexSecret() {
    return HexFormat.of().formatHex(randomBytes(32));
  }

  private record Account(long id, String token) {}

  private record Fixture(
      long eventId, long sessionId, long skuId, Instant startsAt, Instant endsAt) {}

  @FunctionalInterface
  private interface ThrowingIndexedFunction<T> {
    T apply(int index) throws Exception;
  }

  @FunctionalInterface
  private interface ThrowingBooleanSupplier {
    boolean getAsBoolean() throws Exception;
  }

  private static final class RollbackMarker extends RuntimeException {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableTestClock stage3MutableClock() {
      return new MutableTestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
  }

  static final class MutableTestClock extends Clock {
    private final AtomicReference<Instant> instant;

    MutableTestClock(Instant initial) {
      instant = new AtomicReference<>(initial);
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
        throw new IllegalArgumentException("Stage 3 integration tests use UTC only");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
