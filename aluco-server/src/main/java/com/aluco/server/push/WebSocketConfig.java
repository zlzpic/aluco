package com.aluco.server.push;

import com.aluco.server.common.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Registers /ws/live. The handshake interceptor validates the JWT passed
 * as ?token=... and rejects the upgrade with 401 when invalid (spec 5.4).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveWebSocketHandler handler;
    private final JwtService jwtService;

    public WebSocketConfig(LiveWebSocketHandler handler, JwtService jwtService) {
        this.handler = handler;
        this.jwtService = jwtService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/live")
                .addInterceptors(new JwtHandshakeInterceptor(jwtService))
                .setAllowedOrigins("*"); // demo deployment; tighten per environment
    }

    static class JwtHandshakeInterceptor implements HandshakeInterceptor {

        private final JwtService jwtService;

        JwtHandshakeInterceptor(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String token = UriComponentsBuilder.fromUri(request.getURI())
                    .build().getQueryParams().getFirst("token");
            if (token != null && jwtService.isValid(token)) {
                attributes.put("username", jwtService.username(token));
                return true;
            }
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }
}
