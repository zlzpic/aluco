package com.aluco.server.device;

import com.aluco.server.common.DeviceState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Seam 3: latest device state.
 * v1 = MySQL (device_state table); v2 = Redis replacement point (spec 7.2).
 */
public interface StateStore {
    void upsert(DeviceState state);

    /**
     * Write-behind batch for the hot path. One committed transaction per batch instead
     * of one per envelope: at 200 devices x 1s the per-message upsert was the throughput
     * ceiling of the whole pipeline (~34 msg/s on a volume whose commit costs 17ms) and
     * the resulting backlog made presence flap.
     *
     * Impls that do not need it (Redis round trips are sub-millisecond) inherit the loop.
     */
    default void upsertAll(Collection<DeviceState> states) {
        if (states == null) {
            return;
        }
        states.forEach(this::upsert);
    }

    Optional<DeviceState> get(String deviceKey);
    List<DeviceState> list(Collection<String> deviceKeys);

    /** Drop cached state for a deleted device (cache/row cleanup, FK may already cascade). */
    void evictDevice(String deviceKey);
}
