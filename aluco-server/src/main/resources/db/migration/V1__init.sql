-- Aluco v1 schema. Compatible with MySQL 8.4 and H2 (MODE=MySQL, tests only).
-- MySQL 8 defaults to InnoDB / utf8mb4, so table options are omitted intentionally.
-- updated_at is maintained by the application layer (no ON UPDATE clause).

CREATE TABLE device (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        device_key  VARCHAR(64)  NOT NULL UNIQUE,
                        name        VARCHAR(128) NOT NULL,
                        site_id     VARCHAR(64)  NOT NULL,
                        token       VARCHAR(128) NOT NULL,
                        created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE device_state (
                              device_id    BIGINT      PRIMARY KEY,
                              metrics      JSON        NULL,
                              online       TINYINT(1)  NOT NULL DEFAULT 0,
                              last_seen_at DATETIME(3) NULL,
                              CONSTRAINT fk_state_device FOREIGN KEY (device_id) REFERENCES device (id) ON DELETE CASCADE
);

CREATE TABLE telemetry (
                           id        BIGINT AUTO_INCREMENT PRIMARY KEY,
                           device_id BIGINT      NOT NULL,
                           ts        DATETIME(3) NOT NULL,
                           metric    VARCHAR(64) NOT NULL,
                           val       DOUBLE      NOT NULL
--                         CONSTRAINT fk_telemetry_device FOREIGN KEY (device_id) REFERENCES device (id),
--                         KEY idx_dev_metric_ts (device_id, metric, ts)
);
-- NOTE (keep in README/ADR-0004): deliberately no partitioning, no sharding,
-- no covering index. This is the "most reasonable day-one" naive design; its
-- capacity ceiling will be measured by load tests and recorded in a later ADR
-- as the factual basis for storage evolution (v2).

CREATE TABLE alert_rule (
                            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                            name          VARCHAR(128) NOT NULL,
                            metric        VARCHAR(64)  NOT NULL,
                            op            VARCHAR(4)   NOT NULL,
                            threshold_val DOUBLE       NOT NULL,
                            device_key    VARCHAR(64)  NULL,
                            enabled       TINYINT(1)   NOT NULL DEFAULT 1,
                            created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                            updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE alert_event (
                             id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                             rule_id      BIGINT      NOT NULL,
                             device_id    BIGINT      NOT NULL,
                             status       VARCHAR(16) NOT NULL,
                             trigger_val  DOUBLE      NOT NULL,
                             triggered_at DATETIME(3) NOT NULL,
                             resolved_at  DATETIME(3) NULL,
                             acked_at     DATETIME(3) NULL,
                             CONSTRAINT fk_event_rule   FOREIGN KEY (rule_id)   REFERENCES alert_rule (id)
--                           CONSTRAINT fk_event_device FOREIGN KEY (device_id) REFERENCES device (id),
--                           KEY idx_status (status),
--                           KEY idx_device (device_id)
);

CREATE TABLE user (
                      id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                      username      VARCHAR(64)  NOT NULL UNIQUE,
                      password_hash VARCHAR(128) NOT NULL,
                      created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_dev_metric_ts ON telemetry (device_id, metric, ts);
CREATE INDEX idx_status ON alert_event (status);
CREATE INDEX idx_device ON alert_event (device_id);
