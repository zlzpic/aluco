package com.aluco.server.ingestion;

import com.aluco.server.common.DeviceCommand;

/** Downlink command channel (spec 5.3). Publishes to aluco/{siteId}/{deviceKey}/cmd. */
public interface CommandPublisher {
    void publish(String deviceKey, DeviceCommand cmd);

    /**
     * Create and publish a SET_INTERVAL command (spec 5.1.4).
     * @return the command ID
     */
    String publishSetInterval(String deviceKey, int intervalSec);
}
