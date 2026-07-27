package com.aluco.server.common;

import java.util.Map;

/** Downlink command envelope (spec 5.3). v1 supports SET_INTERVAL only. */
public record DeviceCommand(String cmdId, String type, Map<String, Object> params) {

    public static final String TYPE_SET_INTERVAL = "SET_INTERVAL";

    /** Wire form: { "v": 1, "cmdId": ..., "type": ..., "params": {...} } */
    public Map<String, Object> toWire() {
        return Map.of("v", 1, "cmdId", cmdId, "type", type, "params", params);
    }
}
