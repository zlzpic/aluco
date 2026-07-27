package com.aluco.server.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stable cross-version contract for the telemetry envelope (spec 5.2, 12.2).
 * Pure parser/validator with no dependency on other packages; a future Go
 * gateway implements the same contract.
 *
 * Drop rules (caller counts each drop):
 *  - payload larger than 4KB
 *  - unparsable JSON / missing required fields
 *  - v != 1
 *  - deviceId != third topic segment
 *  - ts missing or in the future (60s clock-skew tolerance)
 *  - metrics empty after skipping illegal keys
 */
public class EnvelopeCodec {

    public static final int MAX_PAYLOAD_BYTES = 4096;
    public static final long CLOCK_SKEW_MS = 60_000L;

    private static final Pattern METRIC_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Result of parsing: either a valid message or a machine-readable drop reason. */
    public record ParseResult(TelemetryMessage message, String dropReason) {
        public boolean ok() { return message != null; }
        static ParseResult ok(TelemetryMessage m) { return new ParseResult(m, null); }
        static ParseResult drop(String reason) { return new ParseResult(null, reason); }
    }

    /**
     * @param topic   full MQTT topic, e.g. aluco/site-01/TH-0001/telemetry
     * @param payload raw message bytes
     */
    public ParseResult parse(String topic, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            return ParseResult.drop("PAYLOAD_TOO_LARGE_OR_EMPTY");
        }
        String[] seg = topic.split("/");
        if (seg.length != 4 || !"aluco".equals(seg[0]) || !"telemetry".equals(seg[3])) {
            return ParseResult.drop("BAD_TOPIC");
        }
        String siteId = seg[1];
        String topicDeviceKey = seg[2];

        final JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return ParseResult.drop("BAD_JSON");
        }
        if (root == null || !root.isObject()) {
            return ParseResult.drop("BAD_JSON");
        }

        JsonNode v = root.get("v");
        if (v == null || !v.isInt() || v.intValue() != 1) {
            return ParseResult.drop("UNSUPPORTED_VERSION");
        }

        JsonNode deviceId = root.get("deviceId");
        if (deviceId == null || !deviceId.isTextual() || deviceId.textValue().isBlank()) {
            return ParseResult.drop("MISSING_DEVICE_ID");
        }
        if (!topicDeviceKey.equals(deviceId.textValue())) {
            return ParseResult.drop("DEVICE_TOPIC_MISMATCH");
        }

        JsonNode ts = root.get("ts");
        if (ts == null || !ts.canConvertToLong()) {
            return ParseResult.drop("MISSING_TS");
        }
        long tsMs = ts.longValue();
        if (tsMs > System.currentTimeMillis() + CLOCK_SKEW_MS) {
            return ParseResult.drop("FUTURE_TS");
        }

        long seq = 0L;
        JsonNode seqNode = root.get("seq");
        if (seqNode != null && seqNode.canConvertToLong()) {
            seq = seqNode.longValue();
        }

        JsonNode metricsNode = root.get("metrics");
        if (metricsNode == null || !metricsNode.isObject()) {
            return ParseResult.drop("EMPTY_METRICS");
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metricsNode.fields().forEachRemaining(e -> {
            // illegal keys are skipped, not fatal (spec 5.2)
            if (METRIC_KEY.matcher(e.getKey()).matches() && e.getValue().isNumber()) {
                metrics.put(e.getKey(), e.getValue().doubleValue());
            }
        });
        if (metrics.isEmpty()) {
            return ParseResult.drop("EMPTY_METRICS");
        }

        return ParseResult.ok(new TelemetryMessage(topicDeviceKey, siteId, tsMs, seq, metrics));
    }
}
