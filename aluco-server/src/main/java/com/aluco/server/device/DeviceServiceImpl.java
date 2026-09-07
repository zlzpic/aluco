package com.aluco.server.device;

import com.aluco.server.auth.EmqxAuthService;
import com.aluco.server.common.DeviceState;
import com.aluco.server.common.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final StateStore stateStore;
    private final DeviceTombstoneRepository tombstoneRepository;
    private final EmqxAuthService emqxAuthService;

    public DeviceServiceImpl(DeviceRepository deviceRepository,
                            StateStore stateStore,
                            DeviceTombstoneRepository tombstoneRepository,
                            EmqxAuthService emqxAuthService) {
        this.deviceRepository = deviceRepository;
        this.stateStore = stateStore;
        this.tombstoneRepository = tombstoneRepository;
        this.emqxAuthService = emqxAuthService;
    }

    @Override
    public Device create(CreateDeviceRequest req) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            return deviceRepository.saveAndFlush(
                    new Device(req.deviceKey(), req.name(), req.siteId(), token));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw com.aluco.server.common.BizException.conflict("DEVICE_KEY_EXISTS",
                    "deviceKey already exists: " + req.deviceKey());
        }
    }

    @Override
    public Device get(String deviceKey) {
        return getByKey(deviceKey);
    }

    @Override
    public Device getByKey(String deviceKey) {
        return deviceRepository.findByDeviceKey(deviceKey)
                .orElseThrow(() -> com.aluco.server.common.BizException.notFound("DEVICE_NOT_FOUND",
                        "no such device: " + deviceKey));
    }

    /**
     * Delete device with tombstone semantics (v2 spec 4.4.1):
     * 1. Write tombstone record (preserves device_key for history queries)
     * 2. Revoke EMQX credentials (P1)
     * 3. Delete device (FK cascade removes device_state)
     * 4. Evict cache
     *
     * telemetry / alert_event intentionally preserved (orphan semantics).
     */
    @Override
    @Transactional
    public void delete(String deviceKey) {
        Device device = getByKey(deviceKey);
        Instant now = Instant.now();

        // 1. Write tombstone BEFORE deleting device (preserves device_key for history)
        DeviceTombstone tombstone = new DeviceTombstone(device.getId(), deviceKey, now);
        tombstoneRepository.save(tombstone);

        // 2. Revoke EMQX credentials (P1)
        try {
            emqxAuthService.removeDeviceCredential(deviceKey);
        } catch (Exception e) {
            // Log WARN but don't block deletion; credential cleanup can be manual
            org.slf4j.LoggerFactory.getLogger(DeviceServiceImpl.class)
                    .warn("EMQX credential revocation failed for {}: {}", deviceKey, e.getMessage());
        }

        // 3. Delete device (FK cascade removes device_state)
        deviceRepository.delete(device);

        // 4. Evict cache
        if (stateStore instanceof MySqlStateStore mysqlStore) {
            mysqlStore.evictDevice(deviceKey);
        }
    }

    /**
     * Rotate device token: old token is immediately invalidated (v2 spec 5.2.4).
     * Returns only the new token (displayed once to the user).
     */
    @Transactional
    public String rotateToken(String deviceKey) {
        Device device = getByKey(deviceKey);
        String newToken = UUID.randomUUID().toString().replace("-", "");

        // Update token
        deviceRepository.saveAndFlush(
                new Device(deviceKey, device.getName(), device.getSiteId(), newToken));

        // Sync new token to EMQX (P1)
        try {
            emqxAuthService.rotateDeviceCredential(deviceKey, newToken);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DeviceServiceImpl.class)
                    .warn("EMQX token rotation failed for {}: {}", deviceKey, e.getMessage());
        }

        return newToken;
    }

    @Override
    public Page<Device> page(String keyword, int page, int size) {
        var p = deviceRepository.search(
                (keyword == null || keyword.isBlank()) ? null : keyword,
                org.springframework.data.domain.PageRequest.of(Math.max(0, page - 1), size));
        return Page.of(p.getContent(), p.getTotalElements(), page, size);
    }

    @Override
    public DeviceState getState(String deviceKey) {
        // Check if device exists OR is tombstoned
        Device device = deviceRepository.findByDeviceKey(deviceKey).orElse(null);
        if (device == null) {
            DeviceTombstone tombstone = tombstoneRepository.findByDeviceKey(deviceKey).orElse(null);
            if (tombstone != null) {
                // Device was deleted; return tombstoned state
                return new DeviceState(deviceKey, Map.of(), false, null);
            }
            throw com.aluco.server.common.BizException.notFound("DEVICE_NOT_FOUND",
                    "no such device: " + deviceKey);
        }
        getByKey(deviceKey); // 404 if unknown
        return stateStore.get(deviceKey)
                .orElse(new DeviceState(deviceKey, Map.of(), false, null));
    }
}
