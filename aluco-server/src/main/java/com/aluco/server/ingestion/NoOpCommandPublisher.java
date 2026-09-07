package com.aluco.server.ingestion;

import com.aluco.server.common.DeviceCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op implementation when MQTT is disabled.
 */
@Component
@ConditionalOnProperty(name = "aluco.mqtt.enabled", havingValue = "false")
public class NoOpCommandPublisher implements CommandPublisher {
    @Override
    public void publish(String deviceKey, DeviceCommand cmd) {
        // No-op: MQTT disabled
    }

    @Override
    public String publishSetInterval(String deviceKey, int intervalSec) {
        // No-op: MQTT disabled, return a dummy command ID
        return java.util.UUID.randomUUID().toString();
    }
}