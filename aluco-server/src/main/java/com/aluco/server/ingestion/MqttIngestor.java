package com.aluco.server.ingestion;

import com.aluco.server.common.DeviceCommand;
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

/**
 * Paho-based MQTT endpoint (spec 7.3.1):
 *  - connects after application readiness, subscribes aluco/+/+/telemetry (QoS 1)
 *  - auto-reconnects every aluco.mqtt.reconnect-interval-ms (default 5s)
 *  - every inbound message goes to TelemetryProcessor.onRawMessage
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
    private final long reconnectIntervalMs;
    private final TelemetryProcessor processor;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile MqttClient client;
    private volatile boolean running = false;
    private Thread reconnectThread;

    public MqttIngestor(@Value("${aluco.mqtt.broker}") String broker,
                        @Value("${aluco.mqtt.client-id}") String clientId,
                        @Value("${aluco.mqtt.telemetry-topic}") String telemetryTopic,
                        @Value("${aluco.mqtt.reconnect-interval-ms:5000}") long reconnectIntervalMs,
                        TelemetryProcessor processor,
                        DeviceRepository deviceRepository) {
        this.broker = broker;
        this.clientId = clientId;
        this.telemetryTopic = telemetryTopic;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.processor = processor;
        this.deviceRepository = deviceRepository;
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
        // unique-ish client id avoids clashing with a stale broker session
        MqttClient c = new MqttClient(broker, clientId + "-" + System.currentTimeMillis(),
                new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(false); // we manage retries ourselves
        opts.setConnectionTimeout(10);
        c.setCallback(this);
        c.connect(opts);
        this.client = c;
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
