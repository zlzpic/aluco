package com.aluco.server.alerting;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "alert_rule")
public class AlertRule {

    public enum Op { GT, GTE, LT, LTE, EQ }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Op op;

    @Column(name = "threshold_val", nullable = false)
    private double thresholdVal;

    /** NULL = applies to all devices; otherwise only this device_key (spec 6.4). */
    @Column(name = "device_key", length = 64)
    private String deviceKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AlertRule() {}

    public AlertRule(String name, String metric, Op op, double thresholdVal, String deviceKey) {
        this.name = name;
        this.metric = metric;
        this.op = op;
        this.thresholdVal = thresholdVal;
        this.deviceKey = deviceKey;
        this.updatedAt = Instant.now();
    }

    public boolean violatedBy(double value) {
        return switch (op) {
            case GT  -> value >  thresholdVal;
            case GTE -> value >= thresholdVal;
            case LT  -> value <  thresholdVal;
            case LTE -> value <= thresholdVal;
            case EQ  -> Double.compare(value, thresholdVal) == 0;
        };
    }

    /** true when this rule applies to the given device (spec: null deviceKey = all). */
    public boolean appliesTo(String deviceKey) {
        return this.deviceKey == null || this.deviceKey.equals(deviceKey);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getMetric() { return metric; }
    public Op getOp() { return op; }
    public double getThresholdVal() { return thresholdVal; }
    public String getDeviceKey() { return deviceKey; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }
}
