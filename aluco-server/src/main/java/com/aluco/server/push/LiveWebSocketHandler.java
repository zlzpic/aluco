package com.aluco.server.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * /ws/live endpoint (spec 5.4). Client -> server messages:
 *   { "type": "subscribe",   "deviceIds": [...] }
 *   { "type": "unsubscribe", "deviceIds": [...] }
 * The JWT in ?token=... is validated by the handshake interceptor
 * (WebSocketConfig) before the connection reaches this handler.
 */
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

    private final WebSocketLivePush push;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiveWebSocketHandler(WebSocketLivePush push) {
        this.push = push;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        push.register(session);
        log.debug("WS session {} opened", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            List<String> deviceIds = new ArrayList<>();
            node.path("deviceIds").forEach(n -> deviceIds.add(n.asText()));
            switch (type) {
                case "subscribe" -> push.subscribe(session, deviceIds);
                case "unsubscribe" -> push.unsubscribe(session, deviceIds);
                default -> log.debug("unknown WS message type '{}' from {}", type, session.getId());
            }
        } catch (Exception e) {
            log.warn("bad WS message from {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        push.unregister(session);
        log.debug("WS session {} closed ({})", session.getId(), status);
    }
}
