-- Additive migration: retain all V1/V2 data and constraints.
ALTER TABLE orders
  ADD COLUMN expiry_retry_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
  ADD COLUMN expiry_retry_attempts INT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_orders_expiry_retry CHECK (expiry_retry_attempts BETWEEN 0 AND 30),
  ADD KEY idx_orders_expiry_retry (status, expiry_retry_at, expires_at, id);

-- Deliberately no order FK: event history is independent of aggregate retention.
-- Application service records this row in the same transaction as the state transition.
CREATE TABLE outbox_events (
  event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  lease_owner VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(6) NULL,
  published_at DATETIME(6) NULL,
  last_error VARCHAR(128) NULL,
  PRIMARY KEY (event_id),
  KEY idx_outbox_due (status, next_attempt_at, created_at),
  KEY idx_outbox_lease (status, lease_until),
  KEY idx_outbox_aggregate (aggregate_id, created_at),
  CONSTRAINT ck_outbox_type CHECK (event_type IN ('ORDER_CREATED', 'ORDER_PAID', 'ORDER_CLOSED')),
  CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'CLAIMED', 'SENT')),
  CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_outbox_lease CHECK (
    (status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR
    (status IN ('PENDING', 'SENT') AND lease_owner IS NULL AND lease_until IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE consumed_events (
  event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  consumed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id, consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  message VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_notifications_event UNIQUE (event_id),
  CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_notifications_type CHECK (event_type IN ('ORDER_PAID', 'ORDER_CLOSED')),
  KEY idx_notifications_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
