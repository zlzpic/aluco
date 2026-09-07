package com.aluco.server.common;

/**
 * Parsed command acknowledgment message from device (spec 5.1.2).
 * v: 1, cmdId: uuid, deviceId: "TH-0001", ts: epoch-ms,
 * status: "ACKED" | "FAILED", message: optional reason
 */
public record CmdackMessage(
        int v,
        String cmdId,
        String deviceId,
        long ts,
        String status,
        String message
) {
    public static CmdackMessage of(String cmdId, String deviceId, String status) {
        return new CmdackMessage(1, cmdId, deviceId, System.currentTimeMillis(), status, null);
    }
}
