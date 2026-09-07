package com.aluco.server.device;

import com.aluco.server.common.DeviceState;
import com.aluco.server.common.Page;

public interface DeviceService {
    Device create(CreateDeviceRequest req);
    Device get(String deviceKey);
    /** Get by deviceKey (spec 4.2 #4, avoids `page(1, MAX)` workaround). */
    Device getByKey(String deviceKey);
    void delete(String deviceKey);
    /** Rotate device token: old token invalidated immediately (v2 spec 5.2.4). */
    String rotateToken(String deviceKey);
    Page<Device> page(String keyword, int page, int size);
    DeviceState getState(String deviceKey);
}
