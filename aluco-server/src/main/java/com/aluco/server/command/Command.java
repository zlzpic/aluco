package com.aluco.server.command;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Downlink command record (v2 spec 5.1.3).
 * Stores the lifecycle of a command sent to a device.
 */
@Entity
@Table(name = "command")
public class Command {

    public enum Status { SENT, ACKED, FAILED, TIMEOUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cmd_id", nullable = false, unique = true, length = 36)
    private String cmdId;

    @Column(name = "device_key", nullable = false, length = 64)
    private String deviceKey;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false)
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "acked_at")
    private Instant ackedAt;

    protected Command() {}

    public Command(String cmdId, String deviceKey, String type, String params) {
        this.cmdId = cmdId;
        this.deviceKey = deviceKey;
        this.type = type;
        this.params = params;
        this.status = Status.SENT;
    }

    public void ack(Instant at) {
        this.status = Status.ACKED;
        this.ackedAt = at;
    }

    public void fail(Instant at, String reason) {
        this.status = Status.FAILED;
        this.ackedAt = at;
        // reason could be stored in params or separate column; v2 spec doesn't require it
    }

    public void timeout(Instant at) {
        this.status = Status.TIMEOUT;
        this.ackedAt = at;
    }

    public Long getId() { return id; }
    public String getCmdId() { return cmdId; }
    public String getDeviceKey() { return deviceKey; }
    public String getType() { return type; }
    public String getParams() { return params; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAckedAt() { return ackedAt; }

    public void setStatus(Status status) { this.status = status; }
    public void setAckedAt(Instant ackedAt) { this.ackedAt = ackedAt; }
}
