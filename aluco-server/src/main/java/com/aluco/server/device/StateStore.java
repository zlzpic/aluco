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
    Optional<DeviceState> get(String deviceKey);
    List<DeviceState> list(Collection<String> deviceKeys);

    /** Drop cached state for a deleted device (cache/row cleanup, FK may already cascade). */
    void evictDevice(String deviceKey);
}
