package com.aluco.server.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stable cross-version contract for the telemetry envelope (spec 5.2, 12.2).
 * Also parses cmdack messages (spec 5.1.2).
 */
public class EnvelopeCodec {

    public static final int MAX_PAYLOAD_BYTES = 4096;
    public static final long CLOCK_SKEW_MS = 60_000L;

    private static final Pattern METRIC_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ParseResult(TelemetryMessage message, String dropReason) {
        public boolean ok() { return message != null; }
        static ParseResult ok(TelemetryMessage m) { return new ParseResult(m, null); }
        static ParseResult drop(String reason) { return new ParseResult(null, reason); }
    }

    public record CmdackParseResult(CmdackMessage message, String dropReason) {
        public boolean ok() { return message != null; }
    }

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
            if (METRIC_KEY.matcher(e.getKey()).matches() && e.getValue().isNumber()) {
                metrics.put(e.getKey(), e.getValue().doubleValue());
            }
        });
        if (metrics.isEmpty()) {
            return ParseResult.drop("EMPTY_METRICS");
        }

        return ParseResult.ok(new TelemetryMessage(topicDeviceKey, siteId, tsMs, seq, metrics));
    }

    /**
     * Parse cmdack message (spec 5.1.2):
     * { "v": 1, "cmdId": "uuid", "deviceId": "TH-0001", "ts": 1752739200000,
     *   "status": "ACKED", "message": "interval=5s applied" }
     */
    public CmdackParseResult parseCmdack(String topic, String payload) {
        if (payload == null || payload.isBlank()) {
            return new CmdackParseResult(null, "EMPTY_PAYLOAD");
        }

        String[] seg = topic.split("/");
        if (seg.length != 4 || !"aluco".equals(seg[0]) || !"cmdack".equals(seg[3])) {
            return new CmdackParseResult(null, "BAD_TOPIC");
        }
        String topicDeviceKey = seg[2];

        final JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return new CmdackParseResult(null, "BAD_JSON");
        }
        if (root == null || !root.isObject()) {
            return new CmdackParseResult(null, "BAD_JSON");
        }

        JsonNode v = root.get("v");
        if (v == null || !v.isInt() || v.intValue() != 1) {
            return new CmdackParseResult(null, "UNSUPPORTED_VERSION");
        }

        JsonNode cmdIdNode = root.get("cmdId");
        if (cmdIdNode == null || !cmdIdNode.isTextual()) {
            return new CmdackParseResult(null, "MISSING_CMD_ID");
        }
        String cmdId = cmdIdNode.textValue();

        JsonNode deviceIdNode = root.get("deviceId");
        if (deviceIdNode == null || !deviceIdNode.isTextual()) {
            return new CmdackParseResult(null, "MISSING_DEVICE_ID");
        }
        String deviceId = deviceIdNode.textValue();

        JsonNode statusNode = root.get("status");
        if (statusNode == null || !statusNode.isTextual()) {
            return new CmdackParseResult(null, "MISSING_STATUS");
        }
        String status = statusNode.textValue();

        JsonNode messageNode = root.get("message");
        String message = messageNode != null && messageNode.isTextual() ? messageNode.textValue() : null;

        return new CmdackParseResult(
                new CmdackMessage(1, cmdId, deviceId, System.currentTimeMillis(), status, message),
                null);
    }
}
