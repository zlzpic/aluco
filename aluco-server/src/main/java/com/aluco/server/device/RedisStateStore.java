package com.aluco.server.device;

import com.aluco.server.common.DeviceState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis StateStore implementation (ADR-0010, verification 6.2).
 * Activated when aluco.store.state=redis.
 *
 * Schema:
 *   - aluco:state:{deviceKey} → HASH: metrics(JSON), lastSeenAt(epoch ms), online(0/1)
 *   - aluco:last_seen → ZSET: member=deviceKey, score=lastSeenAt
 *
 * Upsert is a single Lua script (HSET + ZADD) so the state hash and the
 * last-seen index never diverge.
 */
@Repository
@ConditionalOnProperty(name = "aluco.store.state", havingValue = "redis")
public class RedisStateStore implements StateStore {

    private static final String STATE_PREFIX = "aluco:state:";
    private static final String LAST_SEEN_ZSET = "aluco:last_seen";
    private static final TypeReference<Map<String, Double>> METRICS_TYPE = new TypeReference<>() {};

    /** HSET {stateKey} metrics lastSeenAt online + ZADD {zset} score deviceKey, atomically. */
    private static final RedisScript<Long> UPSERT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], 'metrics', ARGV[1], 'lastSeenAt', ARGV[2], 'online', ARGV[3]);"
                    + "redis.call('ZADD', KEYS[2], ARGV[4], KEYS[3]);"
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void upsert(DeviceState state) {
        Map<String, Double> metrics = state.metrics() == null ? Map.of() : state.metrics();
        Long lastSeen = state.lastSeenAt();
        try {
            redis.execute(UPSERT_SCRIPT,
                    List.of(STATE_PREFIX + state.deviceKey(), LAST_SEEN_ZSET, state.deviceKey()),
                    toJson(metrics),
                    lastSeen != null ? String.valueOf(lastSeen) : "",
                    state.online() ? "1" : "0",
                    lastSeen != null ? String.valueOf(lastSeen) : "0");
        } catch (Exception e) {
            throw new IllegalStateException("Redis upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeviceState> get(String deviceKey) {
        Map<Object, Object> entries = redis.opsForHash().entries(STATE_PREFIX + deviceKey);
        return entries.isEmpty() ? Optional.empty() : Optional.of(fromHash(deviceKey, entries));
    }

    @Override
    public List<DeviceState> list(Collection<String> deviceKeys) {
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return List.of();
        }
        List<DeviceState> result = new ArrayList<>();
        for (String deviceKey : deviceKeys) {
            Map<Object, Object> entries = redis.opsForHash().entries(STATE_PREFIX + deviceKey);
            if (!entries.isEmpty()) {
                result.add(fromHash(deviceKey, entries));
            }
        }
        return result;
    }

    @Override
    public void evictDevice(String deviceKey) {
        redis.delete(STATE_PREFIX + deviceKey);
        redis.opsForZSet().remove(LAST_SEEN_ZSET, deviceKey);
    }

    static String stateKey(String deviceKey) {
        return STATE_PREFIX + deviceKey;
    }

    static String lastSeenZset() {
        return LAST_SEEN_ZSET;
    }

    private DeviceState fromHash(String deviceKey, Map<Object, Object> entries) {
        String metricsJson = str(entries.get("metrics"));
        String lastSeen = str(entries.get("lastSeenAt"));
        return new DeviceState(deviceKey,
                fromJson(metricsJson),
                "1".equals(str(entries.get("online"))),
                lastSeen == null || lastSeen.isBlank() ? null : Long.parseLong(lastSeen));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private String toJson(Map<String, Double> metrics) {
        try {
            return mapper.writeValueAsString(metrics);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Double> fromJson(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, METRICS_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}