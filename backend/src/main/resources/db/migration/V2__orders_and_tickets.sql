-- The idempotency row is inserted before inventory locks. requested_sku_id is request
-- semantics, not a foreign key: taking an early shared SKU FK lock would deadlock
-- concurrent reservations when they upgrade to the exclusive inventory lock.
CREATE TABLE orders (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  requested_sku_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',
  total_price_cents BIGINT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  paid_at DATETIME(6) NULL,
  closed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  CONSTRAINT uq_orders_order_no UNIQUE (order_no),
  CONSTRAINT uq_orders_user_idempotency UNIQUE (user_id, idempotency_key),
  KEY idx_orders_user_id (user_id, id),
  KEY idx_orders_expiry (status, expires_at, id),
  CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_orders_request CHECK (requested_sku_id > 0 AND CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 64),
  CONSTRAINT ck_orders_amount CHECK (total_price_cents >= 0),
  CONSTRAINT ck_orders_state CHECK (
    (status = 'PENDING_PAYMENT' AND paid_at IS NULL AND closed_at IS NULL) OR
    (status = 'PAID' AND paid_at IS NOT NULL AND closed_at IS NULL) OR
    (status = 'CLOSED' AND closed_at IS NOT NULL AND paid_at IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  price_cents BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  CONSTRAINT uq_order_items_order UNIQUE (order_id),
  CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_order_items_sku FOREIGN KEY (sku_id) REFERENCES ticket_skus(id),
  CONSTRAINT ck_order_items_price CHECK (price_cents >= 0),
  CONSTRAINT ck_order_items_quantity CHECK (quantity = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tickets (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_item_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  checkin_code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
  used_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  CONSTRAINT uq_tickets_order_item UNIQUE (order_item_id),
  CONSTRAINT uq_tickets_code_hash UNIQUE (checkin_code_hash),
  KEY idx_tickets_owner_id (owner_user_id, id),
  CONSTRAINT fk_tickets_order_item FOREIGN KEY (order_item_id) REFERENCES order_items(id),
  CONSTRAINT fk_tickets_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
  CONSTRAINT ck_tickets_state CHECK (
    (status = 'UNUSED' AND used_at IS NULL) OR (status = 'USED' AND used_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE checkin_records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  operator_user_id BIGINT NOT NULL,
  result VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  CONSTRAINT uq_checkin_records_ticket UNIQUE (ticket_id),
  KEY idx_checkin_records_operator (operator_user_id, created_at),
  CONSTRAINT fk_checkin_records_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
  CONSTRAINT fk_checkin_records_operator FOREIGN KEY (operator_user_id) REFERENCES users(id),
  CONSTRAINT ck_checkin_records_result CHECK (result = 'SUCCESS')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
