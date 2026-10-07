package com.aluco.server.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.lettuce.core.RedisURI;

/**
 * Redis runtime beans for seam 3 (aluco.store.state=redis).
 *
 * Uses the custom key aluco.redis.url (env ALUCO_REDIS_URL) per ADR-0010 /
 * verification 6.2; spring.data.redis.* is intentionally not configured here.
 * Defining our own RedisConnectionFactory makes RedisAutoConfiguration back off,
 * so nothing is wired in default (mysql) mode.
 */
@Configuration
@ConditionalOnProperty(name = "aluco.store.state", havingValue = "redis")
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${aluco.redis.url:redis://localhost:6379}") String url) {
        RedisURI uri = RedisURI.create(url);
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(uri.getHost());
        config.setPort(uri.getPort());
        config.setDatabase(uri.getDatabase() > 0 ? uri.getDatabase() : 0);
        char[] password = uri.getPassword();
        if (password != null && password.length > 0) {
            config.setPassword(new String(password));
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }
}
