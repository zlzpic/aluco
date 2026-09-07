package com.aluco.server.device;

import com.aluco.server.common.DeviceState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MySQL implementation of the StateStore seam (spec 7.3.4):
 * one row per device in device_state,
 * metrics stored as a JSON document, merged-override on each upsert.
 * All device_state SQL lives here (spec 4: no SQL outside store impls).
 */
@Repository
public class MySqlStateStore implements StateStore {

    private static final TypeReference<Map<String, Double>> METRICS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** deviceKey -> device.id cache; invalidated on miss (device may be new) */
    private final Map<String, Long> deviceIds = new ConcurrentHashMap<>();

    public MySqlStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(DeviceState state) {
        String metricsJson = toJson(state.metrics());
        Timestamp lastSeen = state.lastSeenAt() == null ? null : new Timestamp(state.lastSeenAt());
        jdbc.update("INSERT INTO device_state (device_id, metrics, online, last_seen_at) "
                        + "SELECT id, ?, ?, ? FROM device WHERE device_key = ? "
                        + "ON DUPLICATE KEY UPDATE metrics = VALUES(metrics), "
                        + "online = VALUES(online), last_seen_at = VALUES(last_seen_at)",
                metricsJson, state.online() ? 1 : 0, lastSeen, state.deviceKey());
    }

    @Override
    public Optional<DeviceState> get(String deviceKey) {
        List<DeviceState> r = jdbc.query(
                "SELECT d.device_key, s.metrics, s.online, s.last_seen_at "
                        + "FROM device_state s JOIN device d ON d.id = s.device_id "
                        + "WHERE d.device_key = ?",
                (rs, i) -> mapRow(rs.getString(1), rs.getString(2),
                        rs.getInt(3) == 1,
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).getTime()),
                deviceKey);
        return r.stream().findFirst();
    }

    @Override
    public List<DeviceState> list(Collection<String> deviceKeys) {
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = deviceKeys.stream().map(k -> "?").collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT d.device_key, s.metrics, s.online, s.last_seen_at "
                        + "FROM device_state s JOIN device d ON d.id = s.device_id "
                        + "WHERE d.device_key IN (" + placeholders + ")",
                (rs, i) -> mapRow(rs.getString(1), rs.getString(2),
                        rs.getInt(3) == 1,
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).getTime()),
                deviceKeys.toArray());
    }

    private DeviceState mapRow(String deviceKey, String metricsJson, boolean online,
                               Long lastSeenAt) {
        return new DeviceState(deviceKey, fromJson(metricsJson), online, lastSeenAt);
    }

    private String toJson(Map<String, Double> metrics) {
        try {
            return mapper.writeValueAsString(metrics == null ? Map.of() : metrics);
        } catch (Exception e) {
            throw new IllegalStateException("metrics serialization failed", e);
        }
    }

    private Map<String, Double> fromJson(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, METRICS_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
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
