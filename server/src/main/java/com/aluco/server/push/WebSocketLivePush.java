package com.aluco.server.push;

import com.aluco.server.alerting.AlertEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Session registry + fan-out (spec 5.4):
 *  - telemetry: only to sessions subscribed to that device
 *  - alert / presence: broadcast to all sessions
 *  - server sends a ping frame every 30s
 *  - gauge aluco.ws.sessions tracks live session count
 */
@Component
public class WebSocketLivePush implements LivePush {

    private static final Logger log = LoggerFactory.getLogger(WebSocketLivePush.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** live sessions */
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    /** session id -> subscribed device_keys */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public WebSocketLivePush(MeterRegistry registry) {
        Gauge.builder("aluco.ws.sessions", sessions, Set::size).register(registry);
    }

    public void register(WebSocketSession session) {
        sessions.add(session);
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
        subscriptions.remove(session.getId());
    }

    @Override
    public void subscribe(WebSocketSession session, Collection<String> deviceKeys) {
        Set<String> subs = subscriptions.get(session.getId());
        if (subs != null && deviceKeys != null) {
            subs.addAll(deviceKeys);
        }
    }

    @Override
    public void unsubscribe(WebSocketSession session, Collection<String> deviceKeys) {
        Set<String> subs = subscriptions.get(session.getId());
        if (subs != null && deviceKeys != null) {
            deviceKeys.forEach(subs::remove);
        }
    }

    @Override
    public void pushTelemetry(String deviceKey, long ts, Map<String, Double> metrics) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "telemetry");
        msg.put("deviceId", deviceKey);
        msg.put("ts", ts);
        msg.put("metrics", metrics);
        String json = toJson(msg);
        for (WebSocketSession s : sessions) {
            Set<String> subs = subscriptions.get(s.getId());
            if (subs != null && subs.contains(deviceKey)) {
                send(s, json);
            }
        }
    }

    @Override
    public void pushAlert(AlertEvent event) {
        Map<String, Object> ev = new HashMap<>();
        ev.put("id", event.getId());
        ev.put("ruleId", event.getRuleId());
        ev.put("ruleName", event.getRuleName());
        ev.put("deviceId", event.getDeviceKey());
        ev.put("status", event.getStatus().name());
        ev.put("value", event.getTriggerVal());
        ev.put("triggeredAt", event.getTriggeredAt() == null
                ? null : event.getTriggeredAt().toEpochMilli());
        broadcast(Map.of("type", "alert", "event", ev));
    }

    @Override
    public void pushPresence(String deviceKey, boolean online) {
        broadcast(Map.of("type", "presence", "deviceId", deviceKey, "online", online));
    }

    /** Server-side ping every 30s (spec 5.4). */
    @Scheduled(fixedRate = 30_000)
    public void ping() {
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    synchronized (s) {
                        s.sendMessage(new PingMessage());
                    }
                } catch (IOException e) {
                    log.debug("ping failed for session {}: {}", s.getId(), e.getMessage());
                }
            }
        }
    }

    private void broadcast(Map<String, Object> msg) {
        String json = toJson(msg);
        for (WebSocketSession s : sessions) {
            send(s, json);
        }
    }

    private void send(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            return;
        }
        try {
            // WebSocketSession is not thread-safe: serialize sends per session
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.debug("send failed for session {}: {}", session.getId(), e.getMessage());
        }
    }

    private String toJson(Object msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("WS message serialization failed", e);
        }
    }
}
