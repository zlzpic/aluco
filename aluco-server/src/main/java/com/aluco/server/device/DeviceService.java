package com.aluco.server.device;

import com.aluco.server.common.DeviceState;
import com.aluco.server.common.Page;

public interface DeviceService {
    Device create(CreateDeviceRequest req);
    Device get(String deviceKey);
    void delete(String deviceKey);
    Page<Device> page(String keyword, int page, int size);
    DeviceState getState(String deviceKey);
}
