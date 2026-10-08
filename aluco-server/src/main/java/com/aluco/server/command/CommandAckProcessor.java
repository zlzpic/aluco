package com.aluco.server.command;

import com.aluco.server.common.EnvelopeCodec;
import com.aluco.server.common.CmdackMessage;
import com.aluco.server.push.LivePush;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Processes device command acknowledgments from aluco/+/+/cmdack, routed here by
 * MqttIngestor (the single MQTT endpoint). v2 spec 5.1.2.
 */
@Component
public class CommandAckProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandAckProcessor.class);

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final CommandService commandService;
    private final LivePush livePush;

    public CommandAckProcessor(CommandService commandService, LivePush livePush) {
        this.commandService = commandService;
        this.livePush = livePush;
    }

    public void handle(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            EnvelopeCodec.CmdackParseResult result = codec.parseCmdack(topic, payload);

            if (!result.ok()) {
                log.debug("cmdack dropped: {}", result.dropReason());
                return;
            }

            CmdackMessage cmdack = result.message();
            log.info("cmdack received: cmdId={}, status={}", cmdack.cmdId(), cmdack.status());

            // Update command status
            if ("ACKED".equalsIgnoreCase(cmdack.status())) {
                commandService.ack(cmdack.cmdId());
            } else if ("FAILED".equalsIgnoreCase(cmdack.status())) {
                commandService.fail(cmdack.cmdId(), cmdack.message());
            }

            // Broadcast via WebSocket
            livePush.pushCommandEvent(cmdack.cmdId(), cmdack.deviceId(), cmdack.status());

        } catch (Exception e) {
            log.error("cmdack processing error", e);
        }
    }
}
