-- ADR-0011: TimescaleDB schema (seam 2, aluco.store.timeseries=timescale).
-- PostgreSQL 16 + TimescaleDB 2.17 target; 7-day chunks, continuous aggregates,
-- native compression @7d. Retention intentionally disabled (ADR); enable by
-- uncommenting the retention policy at the bottom.

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE IF NOT EXISTS telemetry (
    device_id BIGINT       NOT NULL,
    ts        TIMESTAMPTZ  NOT NULL,
    metric    VARCHAR(64)  NOT NULL,
    val       DOUBLE PRECISION NOT NULL,
    -- UNIQUE includes the partition key (ts): required by hypertables and by
    -- the store's ON CONFLICT (device_id, metric, ts) upsert for at-least-once.
    CONSTRAINT uq_telemetry UNIQUE (device_id, metric, ts)
);

SELECT create_hypertable('telemetry', by_range('ts', INTERVAL '7 days'),
                         if_not_exists => TRUE);

-- Continuous aggregates: AVG(val) per 1 minute / 1 hour.
CREATE MATERIALIZED VIEW IF NOT EXISTS telemetry_1m
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 min', ts) AS bucket,
       device_id,
       metric,
       AVG(val) AS val
FROM telemetry
GROUP BY bucket, device_id, metric
WITH NO DATA;

CREATE MATERIALIZED VIEW IF NOT EXISTS telemetry_1h
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', ts) AS bucket,
       device_id,
       metric,
       AVG(val) AS val
FROM telemetry
GROUP BY bucket, device_id, metric
WITH NO DATA;

-- Refresh continuous aggregates every 1 min (1m view) / 10 min (1h view).
SELECT add_continuous_aggregate_policy('telemetry_1m',
    start_offset => INTERVAL '5 minutes',
    end_offset   => INTERVAL '0 seconds',
    schedule_interval => INTERVAL '1 minute',
    if_not_exists => TRUE);
-- Refresh window must span >= 2 buckets of the aggregate (TimescaleDB error
-- "policy refresh window too small" if narrower). 1h bucket needs >= 2h.
SELECT add_continuous_aggregate_policy('telemetry_1h',
    start_offset => INTERVAL '3 hours',
    end_offset   => INTERVAL '0 seconds',
    schedule_interval => INTERVAL '10 minutes',
    if_not_exists => TRUE);

-- Native compression: chunks older than 7 days, segment by device.
ALTER TABLE telemetry
    SET (timescaledb.compress,
         timescaledb.compress_segmentby = 'device_id',
         timescaledb.compress_orderby   = 'ts,metric');
SELECT add_compression_policy('telemetry', INTERVAL '7 days', if_not_exists => TRUE);

-- Retention is disabled by default (ADR-0011). Enable with:
-- SELECT add_retention_policy('telemetry', INTERVAL '180 days');