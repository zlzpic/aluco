package com.aluco.server.device;

import com.aluco.server.common.BizException;
import com.aluco.server.common.DeviceCommand;
import com.aluco.server.ingestion.CommandPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class DeviceCommandServiceImpl implements DeviceCommandService {

    private final DeviceRepository deviceRepository;
    private final CommandPublisher commandPublisher;

    public DeviceCommandServiceImpl(DeviceRepository deviceRepository,
                                    CommandPublisher commandPublisher) {
        this.deviceRepository = deviceRepository;
        this.commandPublisher = commandPublisher;
    }

    @Override
    public String setReportInterval(String deviceKey, int intervalSec) {
        if (!deviceRepository.existsByDeviceKey(deviceKey)) {
            throw BizException.notFound("DEVICE_NOT_FOUND", "no such device: " + deviceKey);
        }
        if (intervalSec < 1 || intervalSec > 3600) {
            throw BizException.badRequest("INVALID_INTERVAL",
                    "intervalSec must be within 1..3600");
        }
        String cmdId = UUID.randomUUID().toString();
        commandPublisher.publish(deviceKey, new DeviceCommand(
                cmdId, DeviceCommand.TYPE_SET_INTERVAL, Map.of("intervalSec", intervalSec)));
        return cmdId;
    }
}
