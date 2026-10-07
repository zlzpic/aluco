package com.aluco.server.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * AppUser repository (v2 spec 4.4.2: renamed from 'user' table).
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
