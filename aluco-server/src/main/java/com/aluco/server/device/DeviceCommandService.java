package com.aluco.server.device;

public interface DeviceCommandService {
    /** @return cmdId of the published command */
    String setReportInterval(String deviceKey, int intervalSec);
}
