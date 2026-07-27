package com.aluco.server.common;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * HS256 JWT issue/verify (spec 7.4.2). Secret comes from ALUCO_JWT_SECRET
 * (dev default in application.yml — see README warning). TTL 24h by default.
 * Lives in common because both api (REST filter) and push (WS handshake) use it.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlHours;

    public JwtService(@Value("${aluco.jwt.secret}") String secret,
                      @Value("${aluco.jwt.ttl-hours:24}") long ttlHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = ttlHours;
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlHours * 3600)))
                .signWith(key)
                .compact();
    }

    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String username(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    /** epoch ms of expiry for the login response (spec 5.5 #1). */
    public long expiresAt(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getExpiration().getTime();
    }
}
