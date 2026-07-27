package com.aluco.server.ingestion;

/** Lifecycle of the MQTT telemetry subscription (spec 7.3.1). */
public interface TelemetryIngestor {
    void start();
    void stop();
}
