package com.aluco.server.device;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tombstone record for deleted devices (v2 spec 4.4.1).
 * Preserves device_key for historical telemetry/alert queries.
 */
@Entity
@Table(name = "device_tombstone")
public class DeviceTombstone {

    @Id
    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_key", nullable = false, unique = true, length = 64)
    private String deviceKey;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    protected DeviceTombstone() {}

    public DeviceTombstone(Long deviceId, String deviceKey, Instant deletedAt) {
        this.deviceId = deviceId;
        this.deviceKey = deviceKey;
        this.deletedAt = deletedAt;
    }

    public Long getDeviceId() { return deviceId; }
    public String getDeviceKey() { return deviceKey; }
    public Instant getDeletedAt() { return deletedAt; }
}
