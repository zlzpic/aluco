-- Aluco v2 contract migration.
-- Compatible with MySQL 8.4; runs on top of V1__init.sql.
-- Design decisions are recorded in ADR-0008 (orphan semantics, no soft-delete).
--
-- Five categories of change (spec 4.4.2):
-- 1. New table     : device_tombstone
-- 2. New table     : command (v2 spec 5.1.3)
-- 3. alert_event   : + device_key (snapshot) + rule_name (snapshot) + indexes
-- 4. user          : renamed from 'user' -> 'app_user'
-- 5. telemetry     : + UNIQUE KEY (for Kafka at-least-once idempotent write)

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
    INDEX idx_device_key (device_key),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. alert_event: snapshot columns (device_key + rule_name)
--    Snapshot at trigger time; API/WS never depends on device/rule tables.
ALTER TABLE alert_event
    ADD COLUMN device_key  VARCHAR(64)  NULL AFTER device_id,
    ADD COLUMN rule_name   VARCHAR(128) NULL AFTER device_key,
    ADD INDEX idx_device_key (device_key),
    ADD INDEX idx_rule_name  (rule_name);

-- Backfill snapshot from device/rule tables for existing rows
UPDATE alert_event ae
    JOIN device d ON d.id = ae.device_id
    SET ae.device_key = d.device_key
    WHERE ae.device_key IS NULL;

UPDATE alert_event ae
    JOIN alert_rule ar ON ar.id = ae.rule_id
    SET ae.rule_name = ar.name
    WHERE ae.rule_name IS NULL;

-- 4. user -> app_user (root reserved-word workaround)
--    Copy to app_user, then drop user.
CREATE TABLE IF NOT EXISTS app_user LIKE user;

INSERT INTO app_user (id, username, password_hash, created_at)
    SELECT id, username, password_hash, created_at FROM user;

DROP TABLE user;

-- 5. telemetry: unique key for Kafka idempotent write (at-least-once)
--    (device_id, metric, ts) must be unique; INSERT ... ON DUPLICATE KEY UPDATE ignores dupes.
ALTER TABLE telemetry
    ADD UNIQUE KEY uk_dev_metric_ts (device_id, metric, ts);
