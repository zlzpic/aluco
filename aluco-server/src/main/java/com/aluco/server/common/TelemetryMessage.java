package com.aluco.server.common;

import java.util.Map;

/** Standard model produced by the processing module (spec 7.2). */
public record TelemetryMessage(String deviceKey, String siteId, long ts, long seq,
                               Map<String, Double> metrics) {}
