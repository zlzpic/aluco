package com.aluco.server.device;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis OfflineDetection (ADR-0010, verification 6.2).
 * Uses a ZSET range query (no keyspace notifications).
 *
 * Sweeps aluco:last_seen for members with score < cutoff, flips online "1" -> "0"
 * in the state HASH, and returns the deviceKeys that transitioned, so callers
 * can broadcast presence(false). ZSET members are kept (device stays known).
 */
@Repository
@ConditionalOnProperty(name = "aluco.store.state", havingValue = "redis")
public class RedisOfflineDetection implements OfflineDetection {

    private final StringRedisTemplate redis;

    public RedisOfflineDetection(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<String> sweepOffline(long cutoffEpochMs) {
        Set<ZSetOperations.TypedTuple<String>> stale =
                redis.opsForZSet().rangeByScoreWithScores(RedisStateStore.lastSeenZset(),
                        0, (double) cutoffEpochMs);

        if (stale == null || stale.isEmpty()) {
            return List.of();
        }

        List<String> transitioned = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : stale) {
            String deviceKey = tuple.getValue();
            if (deviceKey == null) {
                continue;
            }
            String online = (String) redis.opsForHash().get(
                    RedisStateStore.stateKey(deviceKey), "online");
            if ("1".equals(online)) {
                redis.opsForHash().put(RedisStateStore.stateKey(deviceKey), "online", "0");
                transitioned.add(deviceKey);
            }
        }
        return transitioned;
    }
}