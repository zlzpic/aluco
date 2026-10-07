package com.aluco.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds telemetry envelopes and parses downlink commands (spec 5.2 / 5.3). */
public final class EnvelopeJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnvelopeJson() {}

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /** { "v":1, "deviceId":..., "ts":..., "seq":..., "metrics":{...} } */
    public static byte[] envelope(String deviceId, long ts, long seq, Map<String, Double> metrics) {
        try {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("v", 1);
            env.put("deviceId", deviceId);
            env.put("ts", ts);
            env.put("seq", seq);
            env.put("metrics", metrics);
            return MAPPER.writeValueAsBytes(env);
        } catch (Exception e) {
            throw new IllegalStateException("envelope serialization failed", e);
        }
    }

    /** Parses { "v":1, "cmdId":..., "type":"SET_INTERVAL", "params":{"intervalSec":N} }. */
    public static Command parseCommand(byte[] payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (root == null || !root.isObject() || root.path("v").asInt(-1) != 1) {
                return null;
            }
            String type = root.path("type").asText(null);
            if (!"SET_INTERVAL".equals(type)) {
                return null;
            }
            JsonNode interval = root.path("params").path("intervalSec");
            if (!interval.isNumber()) {
                return null;
            }
            return new Command(root.path("cmdId").asText(""), interval.intValue());
        } catch (Exception e) {
            return null;
        }
    }

    public record Command(String cmdId, int intervalSec) {}
}