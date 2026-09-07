package com.aluco.server.command;

import com.aluco.server.common.EnvelopeCodec;
import com.aluco.server.common.CmdackMessage;
import com.aluco.server.push.LivePush;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to aluco/+/+/cmdack and processes device command acknowledgments.
 * v2 spec 5.1.2.
 */
@Component
public class CommandAckProcessor implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(CommandAckProcessor.class);

    private final CommandService commandService;
    private final LivePush livePush;

    public CommandAckProcessor(CommandService commandService, LivePush livePush) {
        this.commandService = commandService;
        this.livePush = livePush;
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("cmdack connection lost: {}", cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            EnvelopeCodec.CmdackParseResult result = new EnvelopeCodec().parseCmdack(topic, payload);

            if (!result.ok()) {
                log.debug("cmdack dropped: {}", result.dropReason());
                return;
            }

            CmdackMessage cmdack = result.message();
            log.debug("cmdack received: cmdId={}, status={}", cmdack.cmdId(), cmdack.status());

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

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }
}
