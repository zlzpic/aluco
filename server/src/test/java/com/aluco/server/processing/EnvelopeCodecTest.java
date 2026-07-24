package com.aluco.server.processing;

import com.aluco.server.common.EnvelopeCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Every drop branch of the envelope contract (spec 5.2 / 7.4.5). */
class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private static final String TOPIC = "aluco/site-01/TH-0001/telemetry";

    private byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String valid() {
        return "{\"v\":1,\"deviceId\":\"TH-0001\",\"ts\":" + System.currentTimeMillis()
                + ",\"seq\":1,\"metrics\":{\"temp\":35.2,\"humidity\":61.0}}";
    }

    @Test
    void validEnvelopeParses() {
        var r = codec.parse(TOPIC, json(valid()));
        assertThat(r.ok()).isTrue();
        assertThat(r.message().deviceKey()).isEqualTo("TH-0001");
        assertThat(r.message().siteId()).isEqualTo("site-01");
        assertThat(r.message().metrics()).containsEntry("temp", 35.2);
    }

    @Test
    void oversizedPayloadDropped() {
        byte[] big = new byte[EnvelopeCodec.MAX_PAYLOAD_BYTES + 1];
        assertThat(codec.parse(TOPIC, big).dropReason()).isEqualTo("PAYLOAD_TOO_LARGE_OR_EMPTY");
        assertThat(codec.parse(TOPIC, new byte[0]).dropReason()).isEqualTo("PAYLOAD_TOO_LARGE_OR_EMPTY");
    }

    @Test
    void badTopicDropped() {
        assertThat(codec.parse("aluco/site-01/TH-0001/other", json(valid())).dropReason())
                .isEqualTo("BAD_TOPIC");
        assertThat(codec.parse("x/y", json(valid())).dropReason()).isEqualTo("BAD_TOPIC");
    }

    @Test
    void badJsonDropped() {
        assertThat(codec.parse(TOPIC, json("not json")).dropReason()).isEqualTo("BAD_JSON");
        assertThat(codec.parse(TOPIC, json("[1,2]")).dropReason()).isEqualTo("BAD_JSON");
    }

    @Test
    void wrongVersionDropped() {
        assertThat(codec.parse(TOPIC, json(
                        "{\"v\":2,\"deviceId\":\"TH-0001\",\"ts\":1,\"metrics\":{\"t\":1}}"))
                .dropReason()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(codec.parse(TOPIC, json(
                        "{\"deviceId\":\"TH-0001\",\"ts\":1,\"metrics\":{\"t\":1}}"))
                .dropReason()).isEqualTo("UNSUPPORTED_VERSION");
    }

    @Test
    void deviceTopicMismatchDropped() {
        assertThat(codec.parse("aluco/site-01/TH-0002/telemetry", json(valid())).dropReason())
                .isEqualTo("DEVICE_TOPIC_MISMATCH");
    }

    @Test
    void missingOrFutureTsDropped() {
        assertThat(codec.parse(TOPIC, json(
                        "{\"v\":1,\"deviceId\":\"TH-0001\",\"metrics\":{\"t\":1}}"))
                .dropReason()).isEqualTo("MISSING_TS");
        long future = System.currentTimeMillis() + EnvelopeCodec.CLOCK_SKEW_MS + 10_000;
        assertThat(codec.parse(TOPIC, json(
                        "{\"v\":1,\"deviceId\":\"TH-0001\",\"ts\":" + future + ",\"metrics\":{\"t\":1}}"))
                .dropReason()).isEqualTo("FUTURE_TS");
    }

    @Test
    void futureWithinSkewAccepted() {
        long almostNow = System.currentTimeMillis() + 30_000; // within 60s skew
        var r = codec.parse(TOPIC, json(
                "{\"v\":1,\"deviceId\":\"TH-0001\",\"ts\":" + almostNow + ",\"metrics\":{\"t\":1}}"));
        assertThat(r.ok()).isTrue();
    }

    @Test
    void emptyMetricsDropped() {
        assertThat(codec.parse(TOPIC, json(
                "{\"v\":1,\"deviceId\":\"TH-0001\",\"ts\":" + System.currentTimeMillis()
                        + ",\"metrics\":{}}")).dropReason()).isEqualTo("EMPTY_METRICS");
    }

    @Test
    void illegalMetricKeysSkippedRestKept() {
        var r = codec.parse(TOPIC, json(
                "{\"v\":1,\"deviceId\":\"TH-0001\",\"ts\":" + System.currentTimeMillis()
                        + ",\"metrics\":{\"1bad\":1,\"good_key\":2.5,\"bad key\":3}}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.message().metrics()).containsOnly(Map.entry("good_key", 2.5));
    }

    @Test
    void allIllegalKeysDropped() {
        assertThat(codec.parse(TOPIC, json(
                "{\"v\":1,\"deviceId\":\"TH-0001\",\"ts\":" + System.currentTimeMillis()
                        + ",\"metrics\":{\"1bad\":1}}")).dropReason()).isEqualTo("EMPTY_METRICS");
    }

    // needed for Map.entry import clarity
    private static final class Map {
        static java.util.Map.Entry<String, Double> entry(String k, Double v) {
            return java.util.Map.entry(k, v);
        }
    }
}