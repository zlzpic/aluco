-- Aluco v2 contract migration.
-- Compatible with MySQL 8.4 and H2 (MODE=MySQL, used by the test suite) —
-- same dual-dialect constraint as V1: no AFTER clauses, no UPDATE...JOIN,
-- one action per ALTER, standalone CREATE INDEX.
-- Design decisions are recorded in ADR-0008 (orphan semantics, no soft-delete).
--
-- Five categories of change (spec 4.4.2):
-- 1. New table     : device_tombstone
-- 2. New table     : command (v2 spec 5.1.3)
-- 3. alert_event   : + device_key (snapshot) + rule_name (snapshot) + indexes
-- 4. user          : renamed from 'user' -> 'app_user'
-- 5. telemetry     : + UNIQUE KEY (for Kafka at-least-once idempotent write)
--
-- Index names are prefixed per table: H2 keeps index names unique per schema,
-- MySQL scopes them per table; prefixed names satisfy both.

-- 1. device_tombstone: orphan records of deleted devices
CREATE TABLE IF NOT EXISTS device_tombstone (
    device_id   BIGINT        PRIMARY KEY,
    device_key  VARCHAR(64)   NOT NULL UNIQUE,
    deleted_at  DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. command: downlink command lifecycle (v2 spec 5.1.3)
CREATE TABLE IF NOT EXISTS command (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    cmd_id      CHAR(36)     NOT NULL UNIQUE,
    device_key  VARCHAR(64)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    params      JSON         NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    acked_at    DATETIME(3)  NULL,
    INDEX idx_command_device_key (device_key),
    INDEX idx_command_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. alert_event: snapshot columns (device_key + rule_name)
--    Snapshot at trigger time; API/WS never depends on device/rule tables.
ALTER TABLE alert_event ADD COLUMN device_key VARCHAR(64) NULL;
ALTER TABLE alert_event ADD COLUMN rule_name  VARCHAR(128) NULL;
CREATE INDEX idx_alert_device_key ON alert_event (device_key);
CREATE INDEX idx_alert_rule_name  ON alert_event (rule_name);

-- Backfill snapshot from device/rule tables for existing rows
UPDATE alert_event
   SET device_key = (SELECT d.device_key FROM device d WHERE d.id = alert_event.device_id)
 WHERE device_key IS NULL;

UPDATE alert_event
   SET rule_name = (SELECT ar.name FROM alert_rule ar WHERE ar.id = alert_event.rule_id)
 WHERE rule_name IS NULL;

-- 4. user -> app_user (root reserved-word workaround)
--    Copy to app_user, then drop user. Explicit column list instead of
--    CREATE TABLE ... LIKE, which H2 does not support.
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT INTO app_user (id, username, password_hash, created_at)
    SELECT id, username, password_hash, created_at FROM user;

DROP TABLE user;

-- 5. telemetry: unique key for Kafka idempotent write (at-least-once)
--    (device_id, metric, ts) must be unique; INSERT ... ON DUPLICATE KEY UPDATE ignores dupes.
ALTER TABLE telemetry ADD CONSTRAINT uk_dev_metric_ts UNIQUE (device_id, metric, ts);
