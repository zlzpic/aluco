package com.aluco.server.processing;

import com.aluco.server.common.TelemetryPoint;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL implementation of the TimeSeriesStore seam (spec 7.3.3):
 * JdbcTemplate batchUpdate for writes; bucketed AVG via
 * FLOOR(UNIX_TIMESTAMP(ts)/bucket) for down-sampling (spec 5.5, API #7).
 * The only class outside device/* allowed to contain telemetry SQL.
 */
@Repository
@ConditionalOnProperty(name = "aluco.store.timeseries", havingValue = "mysql", matchIfMissing = true)
public class MySqlTimeSeriesStore implements TimeSeriesStore {

    /** raw query hard cap (spec 5.5). Controller sets X-Truncated when hit. */
    public static final int RAW_LIMIT = 10_000;

    private final JdbcTemplate jdbc;
    private final DistributionSummary batchSize;

    /** deviceKey -> device.id cache; invalidated on miss (device may be new) */
    private final Map<String, Long> deviceIds = new ConcurrentHashMap<>();

    public MySqlTimeSeriesStore(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.batchSize = registry.summary("aluco.persist.batch.size");
    }

    /** Bucket width in seconds per interval token; exposed for unit tests. */
    public static long bucketSeconds(String interval) {
        return switch (interval) {
            case "1m" -> 60L;
            case "5m" -> 300L;
            case "1h" -> 3600L;
            case "1d" -> 86400L;
            default -> throw new IllegalArgumentException("unsupported interval: " + interval);
        };
    }

    public static boolean isRaw(String interval) {
        return "raw".equals(interval);
    }

    @Override
    public void writeBatch(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        batchSize.record(points.size());
        jdbc.batchUpdate(
                "INSERT INTO telemetry (device_id, ts, metric, val) VALUES (?, ?, ?, ?)",
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
        long deviceId = deviceId(deviceKey);
        if (isRaw(interval)) {
            List<TelemetryPoint> pts = jdbc.query(
                    "SELECT ts, val FROM telemetry "
                            + "WHERE device_id = ? AND metric = ? AND ts >= ? AND ts <= ? "
                            + "ORDER BY ts LIMIT " + (RAW_LIMIT + 1),
                    (rs, i) -> new TelemetryPoint(deviceKey, metric,
                            rs.getTimestamp(1).getTime(), rs.getDouble(2)),
                    deviceId, metric, new Timestamp(from), new Timestamp(to));
            boolean truncated = pts.size() > RAW_LIMIT;
            if (truncated) {
                pts = new ArrayList<>(pts.subList(0, RAW_LIMIT));
            }
            return new QueryResult(pts, truncated);
        }
        long bucket = bucketSeconds(interval);
        List<TelemetryPoint> bucketed = jdbc.query(
                "SELECT FLOOR(UNIX_TIMESTAMP(ts) / " + bucket + ") * " + bucket + " AS b, "
                        + "AVG(val) FROM telemetry "
                        + "WHERE device_id = ? AND metric = ? AND ts >= ? AND ts <= ? "
                        + "GROUP BY b ORDER BY b",
                (rs, i) -> new TelemetryPoint(deviceKey, metric,
                        rs.getLong(1) * 1000L, rs.getDouble(2)),
                deviceId, metric, new Timestamp(from), new Timestamp(to));
        return new QueryResult(bucketed, false);
    }

    private long deviceId(String deviceKey) {
        Long cached = deviceIds.get(deviceKey);
        if (cached != null) {
            return cached;
        }
        // Check device table first (v2 spec 4.4.1: device ∪ tombstone)
        List<Long> ids = jdbc.query("SELECT id FROM device WHERE device_key = ?",
                (rs, i) -> rs.getLong(1), deviceKey);
        if (ids.isEmpty()) {
            // Check tombstone table for deleted devices (orphan semantics)
            ids = jdbc.query("SELECT device_id FROM device_tombstone WHERE device_key = ?",
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

    /** For tests/ops: current known device ids. */
    public Set<String> cachedDevices() {
        return Set.copyOf(deviceIds.keySet());
    }
}
