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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Default TelemetrySink (spec 7.3.2): ArrayBlockingQueue (capacity 50,000,
 * drop-and-count on overflow) + a single consumer thread fanning out to
 * three paths:
 *   cold  - buffered batch insert via TimeSeriesStore (1,000 pts or 500ms)
 *   hot   - device_state upserts buffered per window (one commit) + LivePush.pushTelemetry
 *   alert - AlertingEngine per data point
 * Also records end-to-end latency (now - envelope.ts) and presence(true)
 * transitions for devices coming back online.
 */
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "aluco.sink.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryTelemetrySink implements TelemetrySink {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTelemetrySink.class);

    private final ArrayBlockingQueue<TelemetryMessage> queue;
    private final TimeSeriesStore timeSeriesStore;
    private final StateStore stateStore;
    private final LivePush livePush;
    private final AlertingEngine alertingEngine;
    private final Counter sinkDropped;
    private final Timer e2eLatency;
    private final PresenceTracker presenceTracker;
    private final int batchMaxSize;
    private final long flushIntervalMs;

    /**
     * Write-behind mirror of device_state.metrics, owned by the consumer thread.
     * The merge-override semantics need the previous metrics; reading them back per
     * envelope was half of the hot-path round trips. Same lifetime (and same bounded
     * size, one entry per fleet key) as MySqlStateStore's id cache.
     */
    private final Map<String, Map<String, Double>> latestMetrics = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private final Thread consumer;

    public InMemoryTelemetrySink(@Value("${aluco.sink.capacity:50000}") int capacity,
                                 @Value("${aluco.batch.max-size:1000}") int batchMaxSize,
                                 @Value("${aluco.batch.flush-interval-ms:500}") long flushIntervalMs,
                                 TimeSeriesStore timeSeriesStore,
                                 StateStore stateStore,
                                 LivePush livePush,
                                 AlertingEngine alertingEngine,
                                 MeterRegistry registry,
                                 PresenceTracker presenceTracker) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchMaxSize = batchMaxSize;
        this.flushIntervalMs = flushIntervalMs;
        this.timeSeriesStore = timeSeriesStore;
        this.stateStore = stateStore;
        this.livePush = livePush;
        this.alertingEngine = alertingEngine;
        this.sinkDropped = registry.counter("aluco.sink.dropped");
        this.e2eLatency = registry.timer("aluco.e2e.latency");
        this.presenceTracker = presenceTracker;
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
        Map<String, DeviceState> pendingStates = new HashMap<>();
        long lastFlush = System.currentTimeMillis();
        while (running || !queue.isEmpty()) {
            try {
                TelemetryMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    dispatch(msg, buffer, pendingStates);
                }
                long now = System.currentTimeMillis();
                if (!buffer.isEmpty()
                        && (buffer.size() >= batchMaxSize || now - lastFlush >= flushIntervalMs)) {
                    flush(buffer, pendingStates);
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
            flush(buffer, pendingStates);
        }
        if (!pendingStates.isEmpty()) {
            stateStore.upsertAll(pendingStates.values());
        }
    }

    private void dispatch(TelemetryMessage msg, List<TelemetryPoint> buffer,
                          Map<String, DeviceState> pendingStates) {
        String deviceKey = msg.deviceKey();

        // cold path: flatten envelope into points
        for (Map.Entry<String, Double> e : msg.metrics().entrySet()) {
            buffer.add(new TelemetryPoint(deviceKey, e.getKey(), msg.ts(), e.getValue()));
        }

        // hot path: merge-override latest metrics, stamp envelope ts, mark online.
        // Buffered here, committed once per flush window by upsertAll.
        Map<String, Double> merged = latestMetrics.computeIfAbsent(deviceKey, k ->
                stateStore.get(k).map(s -> new HashMap<>(s.metrics())).orElseGet(HashMap::new));
        merged.putAll(msg.metrics());
        pendingStates.put(deviceKey, new DeviceState(deviceKey, Map.copyOf(merged), true, msg.ts()));
        livePush.pushTelemetry(deviceKey, msg.ts(), msg.metrics());

        // presence: report coming back online
        presenceTracker.recordReport(deviceKey);

        // alert path
        alertingEngine.onTelemetry(msg);

        // e2e latency: envelope.ts -> processing done (spec 7.3.7)
        e2eLatency.record(System.currentTimeMillis() - msg.ts(), TimeUnit.MILLISECONDS);
    }

    private void flush(List<TelemetryPoint> buffer, Map<String, DeviceState> pendingStates) {
        try {
            timeSeriesStore.writeBatch(List.copyOf(buffer));
        } catch (Exception e) {
            log.error("telemetry batch write failed, {} points lost", buffer.size(), e);
        }
        buffer.clear();

        try {
            if (!pendingStates.isEmpty()) {
                stateStore.upsertAll(pendingStates.values());
            }
        } catch (Exception e) {
            log.error("device_state batch write failed, {} states lost", pendingStates.size(), e);
        }
        pendingStates.clear();
    }

    /** Called by OfflineDetectionTask so a later report triggers presence(true). */
    public void markOffline(String deviceKey) {
        presenceTracker.markOffline(deviceKey);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        running = false;
        consumer.join(5_000);
    }
}
