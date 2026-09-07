package com.aluco.server.processing;

import com.aluco.server.push.LivePush;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Tracks the last-known presence state per device so that a transition
 * from offline → online only broadcasts presence(true) once.
 *
 * Both InMemoryTelemetrySink and KafkaTelemetryConsumer use this;
 * OfflineDetectionTask calls markOffline() to seed the offline state.
 */
@Component
public class PresenceTracker {

    private final LivePush livePush;
    /** last known presence per deviceKey: null = unknown, true = online, false = offline */
    private final Map<String, Boolean> presence = new ConcurrentHashMap<>();

    public PresenceTracker(LivePush livePush) {
        this.livePush = livePush;
    }

    /** Record a telemetry report. Broadcasts presence(true) only on offline→online transition. */
    public void recordReport(String deviceKey) {
        Boolean prev = presence.put(deviceKey, Boolean.TRUE);
        if (prev == null || !prev) {
            livePush.pushPresence(deviceKey, true);
        }
    }

    /** Called by offline sweep. A later report will trigger presence(true). */
    public void markOffline(String deviceKey) {
        presence.put(deviceKey, Boolean.FALSE);
    }
}
