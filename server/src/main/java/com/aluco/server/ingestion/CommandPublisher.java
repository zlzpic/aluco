package com.aluco.server.ingestion;

import com.aluco.server.common.DeviceCommand;

/** Downlink command channel (spec 5.3). Publishes to aluco/{siteId}/{deviceKey}/cmd. */
public interface CommandPublisher {
    void publish(String deviceKey, DeviceCommand cmd);
}
