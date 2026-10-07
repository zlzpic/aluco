package com.aluco.sim;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One simulated device (spec 9.2):
 *  - own MQTT connection (anonymous in v1)
 *  - metric waveforms: temp = 22 + 3*sin(t/10min) + N(0,0.3)
 *                      humidity = 55 + 10*sin(t/17min + phase) + N(0,1)
 *    phase spread by device index
 *  - spike injection: with spikeProbability, temp += 12..18, then linear
 *    decay over the next 3 report cycles (demos alert fire/resolve)
 *  - self-rescheduling report loop so SET_INTERVAL takes effect immediately
 *  - subscribes to its own cmd topic; valid SET_INTERVAL (1..3600) changes the rate
 */
public class SimDevice implements MqttCallback {

    private final String broker;
    private final String siteId;
    private final String deviceKey;
    private final int deviceIndex;
    private final String[] metrics;
    private final double spikeProbability;
    private final double ackDropProbability;
    private final AtomicLong sentTotal;
    private final AtomicLong failureTotal;
    private final ScheduledExecutorService scheduler;

    private final Random random = new Random();
    private final double phase;

    private volatile long intervalMs;
    private volatile boolean running = true;
    private volatile MqttClient client;

    private long seq = 0;
    /** remaining spike-decay cycles and the extra offset to bleed off */
    private int spikeCyclesLeft = 0;
    private double spikeOffset = 0;

    public SimDevice(String broker, String siteId, String deviceKey, int deviceIndex,
                     long intervalMs, String[] metrics, double spikeProbability,
                     double ackDropProbability,
                     AtomicLong sentTotal, AtomicLong failureTotal,
                     ScheduledExecutorService scheduler) {
        this.broker = broker;
        this.siteId = siteId;
        this.deviceKey = deviceKey;
        this.deviceIndex = deviceIndex;
        this.intervalMs = intervalMs;
        this.metrics = metrics;
        this.spikeProbability = spikeProbability;
        this.ackDropProbability = ackDropProbability;
        this.sentTotal = sentTotal;
        this.failureTotal = failureTotal;
        this.scheduler = scheduler;
        this.phase = deviceIndex * 0.7; // spread humidity phase across the fleet
    }

    public void start() {
        try {
            client = new MqttClient(broker, deviceKey + "-sim", new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true); // device-side reconnect is fine for v1
            opts.setConnectionTimeout(10);
            client.setCallback(this);
            client.connect(opts);
            client.subscribe("aluco/" + siteId + "/" + deviceKey + "/cmd", 1);
        } catch (Exception e) {
            failureTotal.incrementAndGet();
            System.err.println("[" + deviceKey + "] connect failed: " + e.getMessage());
            return;
        }
        scheduleNext(0);
    }

    public void stop() {
        running = false;
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
    }

    private void scheduleNext(long delayMs) {
        if (running) {
            scheduler.schedule(this::reportOnce, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void reportOnce() {
        if (!running) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Map<String, Double> values = new LinkedHashMap<>();
            for (String m : metrics) {
                values.put(m, switch (m) {
                    case "temp" -> temp(now);
                    case "humidity" -> humidity(now);
                    default -> 20 + random.nextGaussian(); // generic fallback metric
                });
            }
            MqttClient c = client;
            if (c != null && c.isConnected()) {
                MqttMessage msg = new MqttMessage(
                        EnvelopeJson.envelope(deviceKey, now, ++seq, values));
                msg.setQos(1);
                c.publish("aluco/" + siteId + "/" + deviceKey + "/telemetry", msg);
                sentTotal.incrementAndGet();
            } else {
                failureTotal.incrementAndGet();
            }
        } catch (Exception e) {
            failureTotal.incrementAndGet();
        }
        scheduleNext(intervalMs);
    }

    private double temp(long now) {
        double base = 22 + 3 * Math.sin(now / 600_000.0) + random.nextGaussian() * 0.3;
        // spike injection (spec 9.2): probability -> +12..18, decay over 3 cycles
        if (spikeCyclesLeft == 0 && random.nextDouble() < spikeProbability) {
            spikeOffset = 12 + random.nextDouble() * 6;
            spikeCyclesLeft = 3;
        }
        if (spikeCyclesLeft > 0) {
            double extra = spikeOffset * spikeCyclesLeft / 3.0; // linear falloff
            spikeCyclesLeft--;
            return round2(base + extra);
        }
        return round2(base);
    }

    private double humidity(long now) {
        return round2(55 + 10 * Math.sin(now / 1_020_000.0 + phase) + random.nextGaussian());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ---- cmd topic callback ----

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        EnvelopeJson.Command cmd = EnvelopeJson.parseCommand(message.getPayload());
        if (cmd == null) {
            return;
        }
        if (cmd.intervalSec() < 1 || cmd.intervalSec() > 3600) {
            System.out.println("[" + deviceKey + "] ignored out-of-range SET_INTERVAL "
                    + cmd.intervalSec());
            return; // out-of-range commands are ignored (spec 5.3)
        }
        intervalMs = cmd.intervalSec() * 1000L;
        System.out.println("[" + deviceKey + "] SET_INTERVAL applied: "
                + cmd.intervalSec() + "s (cmdId=" + cmd.cmdId() + ")");

        // Send cmdack (v2 spec 5.1.2)
        sendCmdack(cmd.cmdId(), "ACKED", "interval=" + cmd.intervalSec() + "s applied");
    }

    @Override
    public void connectionLost(Throwable cause) {
        // Paho automaticReconnect handles re-subscription via clean session reconnect;
        // nothing to do here for v1
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // fire-and-forget
    }

    /** Send cmdack for a processed command (v2 spec 5.1.2). */
    private void sendCmdack(String cmdId, String status, String message) {
        // Simulate ack drop probability (spec 9.2: --ack-drop-probability)
        if (ackDropProbability > 0 && random.nextDouble() < ackDropProbability) {
            System.out.println("[" + deviceKey + "] cmdack dropped (simulated) for cmdId=" + cmdId);
            return;
        }
        try {
            if (client == null || !client.isConnected()) {
                return;
            }
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("v", 1);
            ack.put("cmdId", cmdId);
            ack.put("deviceId", deviceKey);
            ack.put("ts", System.currentTimeMillis());
            ack.put("status", status);
            ack.put("message", message);

            byte[] payload = EnvelopeJson.getMapper().writeValueAsBytes(ack);
            MqttMessage msg = new MqttMessage(payload);
            msg.setQos(1);
            client.publish("aluco/" + siteId + "/" + deviceKey + "/cmdack", msg);
            System.out.println("[" + deviceKey + "] cmdack sent: cmdId=" + cmdId + " status=" + status);
        } catch (Exception e) {
            System.err.println("[" + deviceKey + "] cmdack publish failed: " + e.getMessage());
        }
    }
}