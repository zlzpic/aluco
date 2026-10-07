package com.aluco.server.device;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL OfflineDetection implementation (spec 7.3.5):
 * Flips stale online rows to offline and returns the device_keys that transitioned,
 * so callers can broadcast presence(false).
 */
@Repository
@ConditionalOnProperty(name = "aluco.store.state", havingValue = "mysql", matchIfMissing = true)
public class MySqlOfflineDetection implements OfflineDetection {

    private final JdbcTemplate jdbc;
    private final MySqlStateStore stateStore;

    public MySqlOfflineDetection(JdbcTemplate jdbc, MySqlStateStore stateStore) {
        this.jdbc = jdbc;
        this.stateStore = stateStore;
    }

    @Override
    public List<String> sweepOffline(long cutoffEpochMs) {
        // Find stale devices (online=1 AND last_seen_at < cutoff)
        Timestamp cutoff = new Timestamp(cutoffEpochMs);
        List<Long> staleIds = jdbc.query(
                "SELECT s.device_id FROM device_state s "
                        + "WHERE s.online = 1 AND s.last_seen_at < ?",
                (rs, i) -> rs.getLong(1), cutoff);

        if (staleIds.isEmpty()) {
            return List.of();
        }

        // Flip to offline
        String placeholders = staleIds.stream()
                .map(id -> "?").collect(Collectors.joining(","));
        jdbc.update("UPDATE device_state SET online = 0 "
                        + "WHERE device_id IN (" + placeholders + ")",
                staleIds.toArray());

        // Load deviceKeys for the flipped IDs
        List<String> deviceKeys = jdbc.query(
                "SELECT d.device_key FROM device d WHERE d.id IN (" + placeholders + ")",
                (rs, i) -> rs.getString(1), staleIds.toArray());

        // Clear state store cache for flipped devices
        deviceKeys.forEach(stateStore::evictDevice);

        return deviceKeys;
    }
}
