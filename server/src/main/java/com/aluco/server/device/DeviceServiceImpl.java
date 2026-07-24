package com.aluco.server.device;

import com.aluco.server.common.BizException;
import com.aluco.server.common.DeviceState;
import com.aluco.server.common.Page;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final StateStore stateStore;

    public DeviceServiceImpl(DeviceRepository deviceRepository, StateStore stateStore) {
        this.deviceRepository = deviceRepository;
        this.stateStore = stateStore;
    }

    @Override
    public Device create(CreateDeviceRequest req) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            return deviceRepository.saveAndFlush(
                    new Device(req.deviceKey(), req.name(), req.siteId(), token));
        } catch (DataIntegrityViolationException e) {
            throw BizException.conflict("DEVICE_KEY_EXISTS",
                    "deviceKey already exists: " + req.deviceKey());
        }
    }

    @Override
    public Device get(String deviceKey) {
        return deviceRepository.findByDeviceKey(deviceKey)
                .orElseThrow(() -> BizException.notFound("DEVICE_NOT_FOUND",
                        "no such device: " + deviceKey));
    }

    @Override
    @Transactional
    public void delete(String deviceKey) {
        Device device = get(deviceKey);
        // device_state removed via FK ON DELETE CASCADE;
        // telemetry / alert_event intentionally preserved (spec 5.5 #5)
        deviceRepository.delete(device);
    }

    @Override
    public Page<Device> page(String keyword, int page, int size) {
        var p = deviceRepository.search(
                (keyword == null || keyword.isBlank()) ? null : keyword,
                PageRequest.of(Math.max(0, page - 1), size));
        return Page.of(p.getContent(), p.getTotalElements(), page, size);
    }

    @Override
    public DeviceState getState(String deviceKey) {
        get(deviceKey); // 404 if unknown
        return stateStore.get(deviceKey)
                .orElse(new DeviceState(deviceKey, Map.of(), false, null));
    }
}
