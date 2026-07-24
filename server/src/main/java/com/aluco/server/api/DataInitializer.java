package com.aluco.server.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the single v1 admin account (admin / admin123, spec 6.7) when the
 * user table is empty. DEMO CREDENTIALS — never use in production (README).
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;

    public DataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() == 0) {
            String hash = new BCryptPasswordEncoder().encode("admin123");
            userRepository.save(new User("admin", hash));
            log.warn("seeded demo admin account (admin/admin123) — do NOT use in production");
        }
    }
}
