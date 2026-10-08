package com.aluco.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RedisAutoConfiguration is excluded: left to itself Boot wires a default
 * RedisConnectionFactory pointing at localhost:6379 even in mysql mode, and the
 * Redis health indicator then reports the app DOWN for a backend that is not in
 * use. RedisConfig owns those beans and only exists when aluco.store.state=redis.
 */
@EnableScheduling
@SpringBootApplication(exclude = RedisAutoConfiguration.class)
public class AlucoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlucoServerApplication.class, args);
    }
}
