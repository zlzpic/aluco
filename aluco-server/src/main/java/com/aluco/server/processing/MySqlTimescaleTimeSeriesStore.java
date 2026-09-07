package com.aluco.server.processing;

import com.aluco.server.common.TelemetryPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * TimescaleDB TimeSeriesStore implementation (v2 spec 6.3, Phase 2 only).
 * Activated when aluco.store.timeseries=timescale.
 *
 * Schema:
 *   - hypertable telemetry (7-day chunks)
 *   - continuous aggregates: telemetry_1m, telemetry_1h
 *
 * Query routing:
 *   - interval=raw → hypertable direct query
 *   - interval=1m/5m → telemetry_1m
 *   - interval=1h/1d → telemetry_1h
 */
@Repository
@ConditionalOnProperty(name = "aluco.store.timeseries", havingValue = "timescale")
public class MySqlTimescaleTimeSeriesStore implements TimeSeriesStore {

    public static final int RAW_LIMIT = 10_000;

    private final JdbcTemplate jdbc;

    public MySqlTimescaleTimeSeriesStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void writeBatch(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO telemetry (device_id, ts, metric, val) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (device_id, metric, ts) DO UPDATE SET val = EXCLUDED.val",
                points, points.size(),
                (ps, p) -> {
                    ps.setLong(1, deviceId(p.deviceKey()));
                    ps.setTimestamp(2, new Timestamp(p.ts()));
                    ps.setString(3, p.metric());
                    ps.setDouble(4, p.val());
                });
    }

    @Override
    public QueryResult query(String deviceKey, String metric, long from, long to, String interval) {
        if (isRaw(interval)) {
            return queryRaw(deviceKey, metric, from, to);
        } else if ("1m".equals(interval) || "5m".equals(interval)) {
            return queryAggregated(deviceKey, metric, from, to, "telemetry_1m", 60);
        } else {
            return queryAggregated(deviceKey, metric, from, to, "telemetry_1h", 3600);
        }
    }

    private QueryResult queryRaw(String deviceKey, String metric, long from, long to) {
        long deviceId = deviceId(deviceKey);
        List<TelemetryPoint> pts = jdbc.query(
                "SELECT ts, val FROM telemetry " +
                "WHERE device_id = ? AND metric = ? AND ts >= ? AND ts <= ? " +
                "ORDER BY ts LIMIT " + (RAW_LIMIT + 1),
                (rs, i) -> new TelemetryPoint(deviceKey, metric,
                        rs.getTimestamp(1).getTime(), rs.getDouble(2)),
                deviceId, metric, new Timestamp(from), new Timestamp(to));
        boolean truncated = pts.size() > RAW_LIMIT;
        if (truncated) {
            pts = new ArrayList<>(pts.subList(0, RAW_LIMIT));
        }
        return new QueryResult(pts, truncated);
    }

    private QueryResult queryAggregated(String deviceKey, String metric, long from, long to,
                                        String aggTable, long bucketSeconds) {
        long deviceId = deviceId(deviceKey);
        List<TelemetryPoint> pts = jdbc.query(
                "SELECT time_bucket(?, ts) AS bucket, AVG(val) " +
                "FROM " + aggTable + " " +
                "WHERE device_id = ? AND metric = ? AND ts >= ? AND ts <= ? " +
                "GROUP BY bucket ORDER BY bucket",
                (rs, i) -> new TelemetryPoint(deviceKey, metric,
                        rs.getTimestamp(1).getTime(), rs.getDouble(2)),
                java.time.Duration.ofSeconds(bucketSeconds),
                deviceId, metric, new Timestamp(from), new Timestamp(to));
        return new QueryResult(pts, false);
    }

    private long deviceId(String deviceKey) {
        List<Long> ids = jdbc.query(
                "SELECT device_id FROM telemetry WHERE device_key = ? LIMIT 1",
                (rs, i) -> rs.getLong(1), deviceKey);
        if (ids.isEmpty()) {
            throw new IllegalStateException("unknown deviceKey: " + deviceKey);
        }
        return ids.get(0);
    }

    public static boolean isRaw(String interval) {
        return "raw".equals(interval);
    }
}
