package com.aluco.server.processing;

import com.aluco.server.common.EnvelopeCodec;
import com.aluco.server.common.TelemetryMessage;
import com.aluco.server.device.DeviceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Validates envelopes per spec 5.2 and emits valid messages to the sink.
 * Device-registration check (application-layer authn, spec 7.4.1) happens here.
 */
@Component
public class DefaultTelemetryProcessor implements TelemetryProcessor {

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final TelemetrySink sink;
    private final DeviceRepository deviceRepository;
    private final Counter receivedOk;
    private final Counter receivedDropped;

    public DefaultTelemetryProcessor(TelemetrySink sink,
                                     DeviceRepository deviceRepository,
                                     MeterRegistry registry) {
        this.sink = sink;
        this.deviceRepository = deviceRepository;
        this.receivedOk = registry.counter("aluco.ingest.received", "result", "ok");
        this.receivedDropped = registry.counter("aluco.ingest.received", "result", "dropped");
    }

    @Override
    public void onRawMessage(String topic, byte[] payload) {
        EnvelopeCodec.ParseResult result = codec.parse(topic, payload);
        if (!result.ok()) {
            receivedDropped.increment();
            return;
        }
        TelemetryMessage msg = result.message();
        // unregistered device -> drop and count (spec 5.2 / 7.4.1)
        if (!deviceRepository.existsByDeviceKey(msg.deviceKey())) {
            receivedDropped.increment();
            return;
        }
        receivedOk.increment();
        sink.emit(msg);
    }
}
