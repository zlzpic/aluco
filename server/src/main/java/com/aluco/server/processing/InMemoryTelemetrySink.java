package com.aluco.server.processing;

import com.aluco.server.alerting.AlertingEngine;
import com.aluco.server.common.DeviceState;
import com.aluco.server.common.TelemetryMessage;
import com.aluco.server.common.TelemetryPoint;
import com.aluco.server.device.StateStore;
import com.aluco.server.push.LivePush;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Default TelemetrySink (spec 7.3.2): ArrayBlockingQueue (capacity 50,000,
 * drop-and-count on overflow) + a single consumer thread fanning out to
 * three paths:
 *   cold  - buffered batch insert via TimeSeriesStore (1,000 pts or 500ms)
 *   hot   - upsert device_state + LivePush.pushTelemetry
 *   alert - AlertingEngine per data point
 * Also records end-to-end latency (now - envelope.ts) and presence(true)
 * transitions for devices coming back online.
 */
@Component
public class InMemoryTelemetrySink implements TelemetrySink {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTelemetrySink.class);

    private final ArrayBlockingQueue<TelemetryMessage> queue;
    private final TimeSeriesStore timeSeriesStore;
    private final StateStore stateStore;
    private final LivePush livePush;
    private final AlertingEngine alertingEngine;
    private final Counter sinkDropped;
    private final Timer e2eLatency;
    private final int batchMaxSize;
    private final long flushIntervalMs;

    /** last known presence per device, for online transition detection */
    private final Map<String, Boolean> presence = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private final Thread consumer;

    public InMemoryTelemetrySink(@Value("${aluco.sink.capacity:50000}") int capacity,
                                 @Value("${aluco.batch.max-size:1000}") int batchMaxSize,
                                 @Value("${aluco.batch.flush-interval-ms:500}") long flushIntervalMs,
                                 TimeSeriesStore timeSeriesStore,
                                 StateStore stateStore,
                                 LivePush livePush,
                                 AlertingEngine alertingEngine,
                                 MeterRegistry registry) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchMaxSize = batchMaxSize;
        this.flushIntervalMs = flushIntervalMs;
        this.timeSeriesStore = timeSeriesStore;
        this.stateStore = stateStore;
        this.livePush = livePush;
        this.alertingEngine = alertingEngine;
        this.sinkDropped = registry.counter("aluco.sink.dropped");
        this.e2eLatency = registry.timer("aluco.e2e.latency");
        this.consumer = new Thread(this::consumeLoop, "aluco-sink-consumer");
        this.consumer.setDaemon(true);
        this.consumer.start();
    }

    @Override
    public void emit(TelemetryMessage msg) {
        if (!queue.offer(msg)) {
            sinkDropped.increment();
        }
    }

    private void consumeLoop() {
        List<TelemetryPoint> buffer = new ArrayList<>(batchMaxSize);
        long lastFlush = System.currentTimeMillis();
        while (running || !queue.isEmpty()) {
            try {
                TelemetryMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    dispatch(msg, buffer);
                }
                long now = System.currentTimeMillis();
                if (!buffer.isEmpty()
                        && (buffer.size() >= batchMaxSize || now - lastFlush >= flushIntervalMs)) {
                    flush(buffer);
                    lastFlush = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("sink consumer error", e);
            }
        }
        if (!buffer.isEmpty()) {
            flush(buffer);
        }
    }

    private void dispatch(TelemetryMessage msg, List<TelemetryPoint> buffer) {
        String deviceKey = msg.deviceKey();

        // cold path: flatten envelope into points
        for (Map.Entry<String, Double> e : msg.metrics().entrySet()) {
            buffer.add(new TelemetryPoint(deviceKey, e.getKey(), msg.ts(), e.getValue()));
        }

        // hot path: merge-override latest metrics, stamp envelope ts, mark online
        Map<String, Double> merged = stateStore.get(deviceKey)
                .map(s -> new java.util.HashMap<>(s.metrics()))
                .orElseGet(java.util.HashMap::new);
        merged.putAll(msg.metrics());
        stateStore.upsert(new DeviceState(deviceKey, merged, true, msg.ts()));
        livePush.pushTelemetry(deviceKey, msg.ts(), msg.metrics());

        // presence: report coming back online
        Boolean prev = presence.put(deviceKey, Boolean.TRUE);
        if (prev == null || !prev) {
            livePush.pushPresence(deviceKey, true);
        }

        // alert path
        alertingEngine.onTelemetry(msg);

        // e2e latency: envelope.ts -> processing done (spec 7.3.7)
        e2eLatency.record(System.currentTimeMillis() - msg.ts(), TimeUnit.MILLISECONDS);
    }

    private void flush(List<TelemetryPoint> buffer) {
        try {
            timeSeriesStore.writeBatch(List.copyOf(buffer));
        } catch (Exception e) {
            log.error("telemetry batch write failed, {} points lost", buffer.size(), e);
        }
        buffer.clear();
    }

    /** Called by OfflineDetectionTask so a later report triggers presence(true). */
    public void markOffline(String deviceKey) {
        presence.put(deviceKey, Boolean.FALSE);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        running = false;
        consumer.join(5_000);
    }
}
