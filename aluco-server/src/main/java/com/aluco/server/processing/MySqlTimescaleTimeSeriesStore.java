package com.aluco.server.processing;

import com.aluco.server.common.TelemetryPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * TimescaleDB TimeSeriesStore implementation (v2 spec 6.3, ADR-0011).
 * Activated when aluco.store.timeseries=timescale.
 *
 * Schema:
 *   - hypertable telemetry (7-day chunks), UNIQUE (device_id, metric, ts)
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

    private final JdbcTemplate timescale;
    private final JdbcTemplate mysql;

    /** deviceKey -> device.id cache; invalidated on miss (device may be new) */
    private final Map<String, Long> deviceIds = new ConcurrentHashMap<>();

    public MySqlTimescaleTimeSeriesStore(@Qualifier("timescaleJdbc") JdbcTemplate timescale,
                                         @Qualifier("mysqlJdbc") JdbcTemplate mysql) {
        this.timescale = timescale;
        this.mysql = mysql;
    }

    @Override
    public void writeBatch(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        timescale.batchUpdate(
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
            return queryAggregated(deviceKey, metric, from, to, "telemetry_1m");
        } else {
            return queryAggregated(deviceKey, metric, from, to, "telemetry_1h");
        }
    }

    private QueryResult queryRaw(String deviceKey, String metric, long from, long to) {
        long deviceId = deviceId(deviceKey);
        List<TelemetryPoint> pts = timescale.query(
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
                                        String aggTable) {
        long deviceId = deviceId(deviceKey);
        // telemetry_1m/telemetry_1h are already grouped by bucket in the
        // continuous aggregate, so the per-bucket AVG is stored as-is.
        List<TelemetryPoint> pts = timescale.query(
                "SELECT bucket, val FROM " + aggTable + " " +
                "WHERE device_id = ? AND metric = ? AND bucket >= ? AND bucket <= ? " +
                "ORDER BY bucket",
                (rs, i) -> new TelemetryPoint(deviceKey, metric,
                        rs.getTimestamp(1).getTime(), rs.getDouble(2)),
                deviceId, metric, new Timestamp(from), new Timestamp(to));
        return new QueryResult(pts, false);
    }

    /**
     * Resolve device_key -> device.id via device ∪ device_tombstone (v2 4.4.1),
     * same contract as MySqlTimeSeriesStore. Runs on the MySQL pool.
     */
    private long deviceId(String deviceKey) {
        Long cached = deviceIds.get(deviceKey);
        if (cached != null) {
            return cached;
        }
        List<Long> ids = mysql.query("SELECT id FROM device WHERE device_key = ?",
                (rs, i) -> rs.getLong(1), deviceKey);
        if (ids.isEmpty()) {
            ids = mysql.query("SELECT device_id FROM device_tombstone WHERE device_key = ?",
                    (rs, i) -> rs.getLong(1), deviceKey);
            if (ids.isEmpty()) {
                throw new IllegalStateException("unknown deviceKey: " + deviceKey);
            }
        }
        deviceIds.put(deviceKey, ids.get(0));
        return ids.get(0);
    }

    /** For admin/device deletion: evict cache entry. */
    public void evictDevice(String deviceKey) {
        deviceIds.remove(deviceKey);
    }

    public static boolean isRaw(String interval) {
        return "raw".equals(interval);
    }
}