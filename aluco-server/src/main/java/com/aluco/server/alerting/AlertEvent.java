package com.aluco.server.alerting;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "alert_event")
public class AlertEvent {

    public enum Status { FIRING, RESOLVED, ACKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "trigger_val", nullable = false)
    private double triggerVal;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "acked_at")
    private Instant ackedAt;

    /** Snapshot column (v2 spec 4.4.1): device_key at trigger time; never depends on device table. */
    @Column(name = "device_key", length = 64)
    private String deviceKey;

    /** Snapshot column: rule name at trigger time. */
    @Column(name = "rule_name", length = 128)
    private String ruleName;

    /** Populated by services for API/WS rendering (legacy transient, superseded by snap columns). */
    @Transient
    private String ruleNameTransient;
    @Transient
    private String deviceKeyTransient;

    protected AlertEvent() {}

    public AlertEvent(Long ruleId, Long deviceId, double triggerVal, Instant triggeredAt) {
        this.ruleId = ruleId;
        this.deviceId = deviceId;
        this.status = Status.FIRING;
        this.triggerVal = triggerVal;
        this.triggeredAt = triggeredAt;
    }

    public void resolve(Instant at) {
        this.status = Status.RESOLVED;
        this.resolvedAt = at;
    }

    public void ack(Instant at) {
        this.status = Status.ACKED;
        this.ackedAt = at;
    }

    // --- persistent getters ---
    public Long getId() { return id; }
    public Long getRuleId() { return ruleId; }
    public Long getDeviceId() { return deviceId; }
    public Status getStatus() { return status; }
    public double getTriggerVal() { return triggerVal; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getAckedAt() { return ackedAt; }

    // --- snapshot columns (persisted) ---
    public String getDeviceKey() { return deviceKey != null ? deviceKey : deviceKeyTransient; }
    public String getRuleName() { return ruleName != null ? ruleName : ruleNameTransient; }
    public void setDeviceKey(String deviceKey) {
        this.deviceKey = deviceKey;
        this.deviceKeyTransient = deviceKey;
    }
    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
        this.ruleNameTransient = ruleName;
    }
}
