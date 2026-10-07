package com.aluco.server.device;

public interface DeviceCommandService {
    /** @return cmdId of the published command */
    String setReportInterval(String deviceKey, int intervalSec);
    /** Get command by cmdId (v2 spec 5.1.4, REST #17). */
    com.aluco.server.command.Command getCommand(String cmdId);
    /** List recent commands for a device (v2 spec 5.1.4, REST #18). */
    java.util.List<com.aluco.server.command.Command> listCommands(String deviceKey);
}
