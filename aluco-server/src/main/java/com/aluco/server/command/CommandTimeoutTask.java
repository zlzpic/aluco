package com.aluco.server.command;

import com.aluco.server.push.LivePush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweep: mark timed-out SENT commands as TIMEOUT (spec 5.1.4).
 * Default: checks every 10s, timeout threshold = 30s.
 */
@Component
@ConditionalOnProperty(name = "aluco.command.enabled", havingValue = "true", matchIfMissing = true)
public class CommandTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(CommandTimeoutTask.class);

    private final CommandService commandService;
    private final LivePush livePush;
    private final int timeoutSeconds;

    public CommandTimeoutTask(CommandService commandService,
                             LivePush livePush,
                             @Value("${aluco.command.timeout-seconds:30}") int timeoutSeconds) {
        this.commandService = commandService;
        this.livePush = livePush;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${aluco.command.check-interval-ms:10000}")
    public void sweep() {
        try {
            int timedOut = commandService.markTimedOut(timeoutSeconds);
            if (timedOut > 0) {
                log.info("{} commands timed out", timedOut);
            }
        } catch (Exception e) {
            log.error("command timeout sweep failed", e);
        }
    }
}
