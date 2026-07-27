package com.aluco.server.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_key", nullable = false, unique = true, length = 64)
    private String deviceKey;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "site_id", nullable = false, length = 64)
    private String siteId;

    /** Issued once at creation; returned only in the create response (spec 5.5 #2). */
    @Column(nullable = false, length = 128)
    private String token;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {}

    public Device(String deviceKey, String name, String siteId, String token) {
        this.deviceKey = deviceKey;
        this.name = name;
        this.siteId = siteId;
        this.token = token;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeviceKey() { return deviceKey; }
    public String getName() { return name; }
    public String getSiteId() { return siteId; }
    public String getToken() { return token; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
