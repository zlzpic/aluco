package com.aluco.server.processing;

import com.aluco.server.common.TelemetryMessage;

/**
 * Seam 1: telemetry entry point.
 * v1 = in-memory buffered queue; v2 = Kafka replacement point (spec 7.2).
 */
public interface TelemetrySink {
    void emit(TelemetryMessage msg);
}
