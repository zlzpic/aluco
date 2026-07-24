package com.aluco.server.common;

import java.util.Map;

/** Latest known state of one device (hot path model). */
public record DeviceState(String deviceKey, Map<String, Double> metrics,
                          boolean online, Long lastSeenAt) {}
