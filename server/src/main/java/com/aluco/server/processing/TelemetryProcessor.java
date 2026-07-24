package com.aluco.server.processing;

/** Entry from ingestion: validate raw payload, then emit to the sink. */
public interface TelemetryProcessor {
    void onRawMessage(String topic, byte[] payload);
}
