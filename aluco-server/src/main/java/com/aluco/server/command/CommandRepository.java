package com.aluco.server.command;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommandRepository extends JpaRepository<Command, Long> {

    Optional<Command> findByCmdId(String cmdId);

    List<Command> findByDeviceKeyOrderByCreatedAtDesc(String deviceKey);

    @Query("SELECT c FROM Command c WHERE c.deviceKey = :deviceKey AND c.status = :status ORDER BY c.createdAt DESC")
    List<Command> findByDeviceKeyAndStatus(@Param("deviceKey") String deviceKey,
                                          @Param("status") Command.Status status,
                                          Pageable pageable);

    long countByStatus(Command.Status status);
}
