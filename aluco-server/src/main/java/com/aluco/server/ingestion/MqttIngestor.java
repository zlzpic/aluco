package com.aluco.server.ingestion;

import com.aluco.server.common.DeviceCommand;
import com.aluco.server.command.CommandAckProcessor;
import com.aluco.server.command.CommandService;
import com.aluco.server.device.DeviceRepository;
import com.aluco.server.processing.TelemetryProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Paho-based MQTT endpoint (spec 7.3.1):
 *  - connects after application readiness, subscribes aluco/+/+/telemetry and
 *    aluco/+/+/cmdack (QoS 1)
 *  - auto-reconnects every aluco.mqtt.reconnect-interval-ms (default 5s)
 *  - telemetry goes to TelemetryProcessor.onRawMessage, cmdack to CommandAckProcessor
 *  - also serves as CommandPublisher for downlink commands (QoS 1)
 *
 * v1 runs against an anonymous EMQX (spec 7.4.1); device tokens are not
 * enforced at broker level. See README / ADR-0003 for the hardening path.
 */
@Component
@ConditionalOnProperty(name = "aluco.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttIngestor implements TelemetryIngestor, CommandPublisher, MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestor.class);

    private final String broker;
    private final String clientId;
    private final String telemetryTopic;
    private final String cmdackTopic;
    private final long reconnectIntervalMs;
    private final TelemetryProcessor processor;
    private final DeviceRepository deviceRepository;
    private final CommandService commandService;
    private final CommandAckProcessor commandAckProcessor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile MqttClient client;
    private volatile boolean running = false;
    private Thread reconnectThread;

    public MqttIngestor(@Value("${aluco.mqtt.broker}") String broker,
                        @Value("${aluco.mqtt.client-id}") String clientId,
                        @Value("${aluco.mqtt.telemetry-topic}") String telemetryTopic,
                        @Value("${aluco.mqtt.cmdack-topic:aluco/+/+/cmdack}") String cmdackTopic,
                        @Value("${aluco.mqtt.reconnect-interval-ms:5000}") long reconnectIntervalMs,
                        TelemetryProcessor processor,
                        DeviceRepository deviceRepository,
                        CommandService commandService,
                        CommandAckProcessor commandAckProcessor) {
        this.broker = broker;
        this.clientId = clientId;
        this.telemetryTopic = telemetryTopic;
        this.cmdackTopic = cmdackTopic;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.processor = processor;
        this.deviceRepository = deviceRepository;
        this.commandService = commandService;
        this.commandAckProcessor = commandAckProcessor;
    }

    @PostConstruct
    public void start() {
        running = true;
        reconnectThread = new Thread(this::connectLoop, "aluco-mqtt-connector");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
        disconnectQuietly();
    }

    /** Retry forever every reconnectIntervalMs until connected or stopped. */
    private void connectLoop() {
        while (running) {
            try {
                if (client == null || !client.isConnected()) {
                    connect();
                }
                return; // connected; future losses handled by connectionLost()
            } catch (Exception e) {
                log.warn("MQTT connect to {} failed ({}), retry in {} ms",
                        broker, e.getMessage(), reconnectIntervalMs);
                sleep(reconnectIntervalMs);
            }
        }
    }

    private void connect() throws Exception {
        disconnectQuietly();
        MqttClient c = new MqttClient(broker, clientId + "-" + System.currentTimeMillis(),
                new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(false); // we manage retries ourselves
        opts.setConnectionTimeout(10);
        this.client = c;
        c.setCallback(this);
        c.connect(opts);
        log.info("MQTT connected to {}", broker);
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            client.subscribe(telemetryTopic, 1);
            log.info("subscribed to {} (QoS 1)", telemetryTopic);
        } catch (Exception e) {
            log.error("subscribe to {} failed", telemetryTopic, e);
        }
        try {
            client.subscribe(cmdackTopic, 1);
            log.info("subscribed to {} (QoS 1)", cmdackTopic);
        } catch (Exception e) {
            log.error("subscribe to {} failed", cmdackTopic, e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
        if (running) {
            reconnectThread = new Thread(this::connectLoop, "aluco-mqtt-connector");
            reconnectThread.setDaemon(true);
            reconnectThread.start();
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            if (topic.endsWith("/cmdack")) {
                commandAckProcessor.handle(topic, message);
                return;
            }
            processor.onRawMessage(topic, message.getPayload());
        } catch (Exception e) {
            log.error("error processing inbound message on {}", topic, e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op: QoS 1 publishes are fire-and-forget for v1
    }

    /** Publishes a downlink command to aluco/{siteId}/{deviceKey}/cmd (QoS 1). */
    @Override
    public void publish(String deviceKey, DeviceCommand cmd) {
        try {
            String siteId = deviceRepository.findByDeviceKey(deviceKey)
                    .map(d -> d.getSiteId())
                    .orElseThrow(() -> new IllegalArgumentException("unknown device: " + deviceKey));
            String topic = "aluco/" + siteId + "/" + deviceKey + "/cmd";
            byte[] payload = objectMapper.writeValueAsString(cmd.toWire())
                    .getBytes(StandardCharsets.UTF_8);
            MqttClient c = this.client;
            if (c == null || !c.isConnected()) {
                throw new IllegalStateException("MQTT not connected");
            }
            c.publish(topic, new MqttMessage(payload) {{ setQos(1); }});
            log.info("command {} published to {}", cmd.cmdId(), topic);
        } catch (Exception e) {
            throw new RuntimeException("failed to publish command to " + deviceKey, e);
        }
    }

    /**
     * Create and publish a SET_INTERVAL command (spec 5.1.4 flow).
     * Deliberately not @Transactional: createSetInterval must COMMIT before the
     * publish, otherwise a fast device can send its cmdack while the command row
     * is still invisible, and the ack is lost as "unknown cmdId".
     */
    @Override
    public String publishSetInterval(String deviceKey, int intervalSec) {
        // 0. Fail fast with 404 — otherwise we would persist a command row for a device that
        // can never receive it, and the publish failure below would surface as a 500.
        deviceRepository.findByDeviceKey(deviceKey)
                .orElseThrow(() -> com.aluco.server.common.BizException.notFound(
                        "DEVICE_NOT_FOUND", "no such device: " + deviceKey));

        // 1. Persist
        com.aluco.server.command.Command cmd = commandService.createSetInterval(deviceKey, intervalSec);

        // 2. Publish to MQTT
        try {
            DeviceCommand wireCmd = new DeviceCommand(
                    cmd.getCmdId(),
                    DeviceCommand.TYPE_SET_INTERVAL,
                    Map.of("intervalSec", intervalSec)
            );
            publish(deviceKey, wireCmd);
        } catch (Exception e) {
            // Publish failure; command stays in SENT state for retry or timeout
            throw new RuntimeException("failed to publish command to " + deviceKey, e);
        }

        return cmd.getCmdId();
    }

    private void disconnectQuietly() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (Exception ignored) {
            // shutting down anyway
        }
        client = null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
