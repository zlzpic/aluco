package com.aluco.server.command;

import com.aluco.server.common.DeviceCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command service: creates commands, tracks lifecycle, handles timeout.
 * v2 spec 5.1.4.
 */
@Service
public class CommandService {

    private final CommandRepository commandRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CommandService(CommandRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    /**
     * Create a new SET_INTERVAL command (spec 5.1.4).
     */
    @Transactional
    public Command createSetInterval(String deviceKey, int intervalSec) {
        String cmdId = UUID.randomUUID().toString();
        // command.params is a MySQL JSON column: Map.toString() ("{intervalSec=5}") is rejected.
        String params;
        try {
            params = objectMapper.writeValueAsString(Map.of("intervalSec", intervalSec));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("command params serialization failed", e);
        }
        Command cmd = new Command(cmdId, deviceKey, DeviceCommand.TYPE_SET_INTERVAL, params);
        return commandRepository.save(cmd);
    }

    /**
     * Record ACK from device (spec 5.1.4).
     */
    @Transactional
    public void ack(String cmdId) {
        Command cmd = commandRepository.findByCmdId(cmdId)
                .orElseThrow(() -> new IllegalArgumentException("unknown cmdId: " + cmdId));
        if (cmd.getStatus() != Command.Status.SENT) {
            return; // already resolved
        }
        cmd.ack(Instant.now());
        commandRepository.save(cmd);
    }

    /**
     * Record FAILED from device (spec 5.1.4).
     */
    @Transactional
    public void fail(String cmdId, String reason) {
        Command cmd = commandRepository.findByCmdId(cmdId)
                .orElseThrow(() -> new IllegalArgumentException("unknown cmdId: " + cmdId));
        if (cmd.getStatus() != Command.Status.SENT) {
            return;
        }
        cmd.fail(Instant.now(), reason);
        commandRepository.save(cmd);
    }

    /**
     * Get command by cmdId (spec 5.1.4, REST #17).
     */
    public Command getByCmdId(String cmdId) {
        return commandRepository.findByCmdId(cmdId)
                .orElseThrow(() -> new IllegalArgumentException("unknown cmdId: " + cmdId));
    }

    /**
     * List recent commands for a device (spec 5.1.4, REST #18).
     */
    public List<Command> listByDevice(String deviceKey, int page, int size) {
        return commandRepository.findByDeviceKeyOrderByCreatedAtDesc(deviceKey);
    }

    /**
     * Mark timed-out SENT commands (spec 5.1.4: timeout = 30s).
     */
    @Transactional
    public int markTimedOut(int timeoutSeconds) {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);
        // Find SENT commands older than timeout
        List<Command> timedOut = commandRepository.findAll().stream()
                .filter(c -> c.getStatus() == Command.Status.SENT)
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isBefore(cutoff))
                .toList();

        for (Command cmd : timedOut) {
            cmd.timeout(Instant.now());
            commandRepository.save(cmd);
        }
        return timedOut.size();
    }
}
