package com.aluco.server.ingestion;

import com.aluco.server.command.Command;
import com.aluco.server.command.CommandService;
import com.aluco.server.common.DeviceCommand;
import com.aluco.server.device.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Enhanced CommandPublisher: persists to DB before publishing (spec 5.1.4).
 */
@Service
@ConditionalOnProperty(name = "aluco.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class CommandPublisherImpl {

    private final MqttIngestor mqttIngestor;
    private final CommandService commandService;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CommandPublisherImpl(MqttIngestor mqttIngestor,
                                CommandService commandService,
                                DeviceRepository deviceRepository) {
        this.mqttIngestor = mqttIngestor;
        this.commandService = commandService;
        this.deviceRepository = deviceRepository;
    }

    /**
     * Create and publish a SET_INTERVAL command (spec 5.1.4 flow).
     */
    @Transactional
    public String publishSetInterval(String deviceKey, int intervalSec) {
        // 1. Persist
        Command cmd = commandService.createSetInterval(deviceKey, intervalSec);

        // 2. Publish to MQTT
        try {
            DeviceCommand wireCmd = new DeviceCommand(
                    cmd.getCmdId(),
                    DeviceCommand.TYPE_SET_INTERVAL,
                    Map.of("intervalSec", intervalSec)
            );
            mqttIngestor.publish(deviceKey, wireCmd);
        } catch (Exception e) {
            // Publish failure; command stays in SENT state for retry or timeout
            throw new RuntimeException("failed to publish command to " + deviceKey, e);
        }

        return cmd.getCmdId();
    }
}
