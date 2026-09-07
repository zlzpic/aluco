package com.aluco.server.device;

import java.util.List;

/**
 * Redis OfflineDetection implementation (v2 spec 6.2).
 * Uses ZSET range query instead of keyspace notifications (explicitly rejected).
 */
public interface OfflineDetection {
    /**
     * Sweep for stale devices and flip them offline.
     *
     * @param cutoffEpochMs devices with lastSeenAt < this are candidates
     * @return deviceKeys that transitioned from online to offline (for presence broadcast)
     */
    List<String> sweepOffline(long cutoffEpochMs);
}
