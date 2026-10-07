package com.aluco.server.push;

import com.aluco.server.alerting.AlertEvent;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;

public interface LivePush {
    void pushTelemetry(String deviceKey, long ts, Map<String, Double> metrics);
    void pushAlert(AlertEvent event);
    void pushPresence(String deviceKey, boolean online);
    void subscribe(WebSocketSession session, Collection<String> deviceKeys);
    void unsubscribe(WebSocketSession session, Collection<String> deviceKeys);
    void pushCommandEvent(String cmdId, String deviceKey, String status);
}
