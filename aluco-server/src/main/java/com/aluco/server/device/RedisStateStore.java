package com.aluco.server.device;

import com.aluco.server.common.DeviceState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis StateStore implementation (v2 spec 6.2, Phase 2 only).
 * Activated when aluco.store.state=redis.
 *
 * Schema:
 *   - aluco:state:{deviceKey} → HASH: metrics(JSON), lastSeenAt(epoch ms), online(0/1)
 *   - aluco:last_seen → ZSET: member=deviceKey, score=lastSeenAt
 */
@Repository
@ConditionalOnProperty(name = "aluco.store.state", havingValue = "redis")
public class RedisStateStore implements StateStore {

    private static final String STATE_PREFIX = "aluco:state:";
    private static final String LAST_SEEN_ZSET = "aluco:last_seen";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void upsert(DeviceState state) {
        String key = STATE_PREFIX + state.deviceKey();
        Map<String, String> hash = new HashMap<>();
        hash.put("metrics", toJson(state.metrics()));
        hash.put("lastSeenAt", String.valueOf(state.lastSeenAt() != null ? state.lastSeenAt() : 0));
        hash.put("online", state.online() ? "1" : "0");

        try {
            String hashJson = mapper.writeValueAsString(hash);
            redis.opsForValue().set(key, hashJson);
            redis.opsForZSet().add(LAST_SEEN_ZSET, state.deviceKey(),
                    state.lastSeenAt() != null ? state.lastSeenAt() : 0);
        } catch (Exception e) {
            throw new IllegalStateException("Redis upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeviceState> get(String deviceKey) {
        String key = STATE_PREFIX + deviceKey;
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            Map<String, String> map = mapper.readValue(json, Map.class);
            Map<String, Double> metrics = fromJson(map.getOrDefault("metrics", "{}"));
            long lastSeen = Long.parseLong(map.getOrDefault("lastSeenAt", "0"));
            boolean online = "1".equals(map.getOrDefault("online", "0"));
            return Optional.of(new DeviceState(deviceKey, metrics, online, lastSeen));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<DeviceState> list(Collection<String> deviceKeys) {
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return List.of();
        }
        List<String> keys = deviceKeys.stream()
                .map(k -> STATE_PREFIX + k)
                .collect(Collectors.toList());
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<DeviceState> result = new ArrayList<>();
        Iterator<String> deviceKeyIter = deviceKeys.iterator();
        for (String value : values) {
            if (value == null) {
                deviceKeyIter.next();
                continue;
            }
            String deviceKey = deviceKeyIter.next();
            try {
                Map<String, String> map = mapper.readValue(value, Map.class);
                Map<String, Double> metrics = fromJson(map.getOrDefault("metrics", "{}"));
                long lastSeen = Long.parseLong(map.getOrDefault("lastSeenAt", "0"));
                boolean online = "1".equals(map.getOrDefault("online", "0"));
                result.add(new DeviceState(deviceKey, metrics, online, lastSeen));
            } catch (Exception e) {
                // skip corrupted entry
            }
        }
        return result;
    }

    /** Evict device from cache (used on device deletion). */
    public void evictDevice(String deviceKey) {
        String key = STATE_PREFIX + deviceKey;
        redis.delete(key);
        redis.opsForZSet().remove(LAST_SEEN_ZSET, deviceKey);
    }

    private String toJson(Map<String, Double> metrics) {
        try {
            return mapper.writeValueAsString(metrics);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> fromJson(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
