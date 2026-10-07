package com.aluco.server.device;

import com.aluco.server.processing.PresenceTracker;
import com.aluco.server.push.LivePush;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 *
 * Uses OfflineDetection seam (spec 4.3): v1 = MySqlOfflineDetection; v2 = Redis replacement point.
 */
@Component
@ConditionalOnProperty(name = "aluco.offline.enabled", havingValue = "true", matchIfMissing = true)
public class OfflineDetectionTask {

    private static final Logger log = LoggerFactory.getLogger(OfflineDetectionTask.class);

    private final OfflineDetection offlineDetection;
    private final LivePush livePush;
    private final PresenceTracker presenceTracker;
    private final Counter offlineFlips;
    private final int thresholdSeconds;

    public OfflineDetectionTask(OfflineDetection offlineDetection,
                                LivePush livePush,
                                PresenceTracker presenceTracker,
                                MeterRegistry registry,
                                @Value("${aluco.offline.threshold-seconds:60}") int thresholdSeconds) {
        this.offlineDetection = offlineDetection;
        this.livePush = livePush;
        this.presenceTracker = presenceTracker;
        this.offlineFlips = registry.counter("aluco.offline.flips");
        this.thresholdSeconds = thresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${aluco.offline.check-interval-ms:15000}")
    public void sweep() {
        try {
            long cutoff = System.currentTimeMillis() - thresholdSeconds * 1000L;
            for (String deviceKey : offlineDetection.sweepOffline(cutoff)) {
                offlineFlips.increment();
                presenceTracker.markOffline(deviceKey);
                livePush.pushPresence(deviceKey, false);
                log.info("device {} went offline", deviceKey);
            }
        } catch (Exception e) {
            log.error("offline sweep failed", e);
        }
    }
}
