package com.aluco.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AlucoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlucoServerApplication.class, args);
    }
}
