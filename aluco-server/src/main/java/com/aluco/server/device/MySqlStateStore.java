package com.aluco.server.device;

import com.aluco.server.common.DeviceState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "aluco.store.state", havingValue = "mysql", matchIfMissing = true)
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

    /** Rows per statement in upsertAll; keeps a single multi-row statement parseable. */
    private static final int UPSERT_CHUNK = 500;

    @Override
    public void upsertAll(Collection<DeviceState> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        List<DeviceState> batch = new ArrayList<>(states);
        Map<String, Long> ids = resolveDeviceIds(batch);
        for (int from = 0; from < batch.size(); from += UPSERT_CHUNK) {
            List<DeviceState> chunk = batch.subList(from, Math.min(from + UPSERT_CHUNK, batch.size()));
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO device_state (device_id, metrics, online, last_seen_at) VALUES ");
            List<Object> args = new ArrayList<>(chunk.size() * 4);
            for (DeviceState state : chunk) {
                Long deviceId = ids.get(state.deviceKey());
                if (deviceId == null) {
                    continue; // device removed mid-flight; nothing to persist
                }
                sql.append(args.isEmpty() ? "(?,?,?,?)" : ",(?,?,?,?)");
                args.add(deviceId);
                args.add(toJson(state.metrics()));
                args.add(state.online() ? 1 : 0);
                args.add(state.lastSeenAt() == null ? null : new Timestamp(state.lastSeenAt()));
            }
            if (args.isEmpty()) {
                continue;
            }
            sql.append(" ON DUPLICATE KEY UPDATE metrics = VALUES(metrics), ")
                    .append("online = VALUES(online), last_seen_at = VALUES(last_seen_at)");
            jdbc.update(sql.toString(), args.toArray());
        }
    }

    /** One SELECT for every deviceKey not already cached (ids are immutable per key). */
    private Map<String, Long> resolveDeviceIds(List<DeviceState> states) {
        Set<String> missing = new java.util.HashSet<>();
        for (DeviceState state : states) {
            if (!deviceIds.containsKey(state.deviceKey())) {
                missing.add(state.deviceKey());
            }
        }
        if (!missing.isEmpty()) {
            String placeholders = missing.stream().map(k -> "?").collect(Collectors.joining(","));
            jdbc.query("SELECT id, device_key FROM device WHERE device_key IN (" + placeholders + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            deviceIds.put(rs.getString("device_key"), rs.getLong("id")),
                    missing.toArray());
        }
        return deviceIds;
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

    /** For admin/device deletion: evict cache entry (device_state row is FK-cascaded). */
    @Override
    public void evictDevice(String deviceKey) {
        deviceIds.remove(deviceKey);
    }

    /** For tests/ops: current known device ids. */
    public Set<String> cachedDevices() {
        return Set.copyOf(deviceIds.keySet());
    }
}
