-- All timestamps are UTC; clients and pool connections use UTC as well.
CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(254) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
  user_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (user_id, role),
  CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_user_roles_role CHECK (role IN ('USER', 'ORGANIZER', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  organizer_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  description TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  KEY idx_events_status_id (status, id),
  KEY idx_events_organizer_id (organizer_id, id),
  CONSTRAINT fk_events_organizer FOREIGN KEY (organizer_id) REFERENCES users(id),
  CONSTRAINT ck_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_sessions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NOT NULL,
  location VARCHAR(160) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  KEY idx_sessions_event_start (event_id, starts_at),
  CONSTRAINT fk_sessions_event FOREIGN KEY (event_id) REFERENCES events(id),
  CONSTRAINT ck_sessions_time CHECK (starts_at < ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ticket_skus (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  price_cents BIGINT NOT NULL,
  total_stock INT NOT NULL,
  available_stock INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (id),
  CONSTRAINT uq_skus_session_name UNIQUE (session_id, name),
  CONSTRAINT fk_skus_session FOREIGN KEY (session_id) REFERENCES event_sessions(id),
  CONSTRAINT ck_skus_price CHECK (price_cents >= 0),
  CONSTRAINT ck_skus_stock CHECK (total_stock >= 0 AND available_stock >= 0 AND available_stock <= total_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
