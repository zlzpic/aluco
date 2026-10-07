package com.aluco.server.device;

import com.aluco.server.command.Command;
import com.aluco.server.command.CommandRepository;
import com.aluco.server.command.CommandService;
import com.aluco.server.ingestion.CommandPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Device command service: creates downlink commands and tracks their lifecycle.
 * v2 spec 5.1.4.
 */
@Service
public class DeviceCommandServiceImpl implements DeviceCommandService {

    private final CommandService commandService;
    private final CommandPublisher commandPublisher;
    private final CommandRepository commandRepository;

    public DeviceCommandServiceImpl(CommandService commandService,
                                    CommandPublisher commandPublisher,
                                    CommandRepository commandRepository) {
        this.commandService = commandService;
        this.commandPublisher = commandPublisher;
        this.commandRepository = commandRepository;
    }

    /**
     * Send SET_INTERVAL command to a device (spec 5.1.4, REST #14 revised).
     */
    @Transactional
    public String setReportInterval(String deviceKey, int intervalSec) {
        return commandPublisher.publishSetInterval(deviceKey, intervalSec);
    }

    /**
     * Get command by cmdId (spec 5.1.4, REST #17).
     */
    public Command getCommand(String cmdId) {
        return commandService.getByCmdId(cmdId);
    }

    /**
     * List recent commands for a device (spec 5.1.4, REST #18).
     */
    public List<Command> listCommands(String deviceKey) {
        return commandService.listByDevice(deviceKey, 1, 100);
    }
}
