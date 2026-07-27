package com.aluco.server.device;

import com.aluco.server.processing.InMemoryTelemetrySink;
import com.aluco.server.push.LivePush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spec 7.3.5: every 15s, devices with last_seen_at older than the offline
 * threshold (default 60s) flip to offline; each transition broadcasts
 * presence(false) and is remembered by the sink so the next report
 * triggers presence(true).
 */
@Component
@ConditionalOnProperty(name = "aluco.offline.enabled", havingValue = "true", matchIfMissing = true)
public class OfflineDetectionTask {

    private static final Logger log = LoggerFactory.getLogger(OfflineDetectionTask.class);

    private final MySqlStateStore stateStore;
    private final LivePush livePush;
    private final InMemoryTelemetrySink sink;
    private final int thresholdSeconds;

    public OfflineDetectionTask(MySqlStateStore stateStore,
                                LivePush livePush,
                                InMemoryTelemetrySink sink,
                                @Value("${aluco.offline.threshold-seconds:60}") int thresholdSeconds) {
        this.stateStore = stateStore;
        this.livePush = livePush;
        this.sink = sink;
        this.thresholdSeconds = thresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${aluco.offline.check-interval-ms:15000}")
    public void sweep() {
        try {
            for (String deviceKey : stateStore.flipStaleToOffline(thresholdSeconds)) {
                sink.markOffline(deviceKey);
                livePush.pushPresence(deviceKey, false);
                log.info("device {} went offline", deviceKey);
            }
        } catch (Exception e) {
            log.error("offline sweep failed", e);
        }
    }
}
