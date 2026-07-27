package com.aluco.server.common;

/** A single metric data point flattened from a TelemetryMessage. */
public record TelemetryPoint(String deviceKey, String metric, long ts, double val) {}
