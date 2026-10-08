package com.aluco.server.alerting;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    List<AlertEvent> findByStatus(AlertEvent.Status status);

    /** Spring Data derived query — used by resolve() to find the open event (no custom JPQL needed). */
    List<AlertEvent> findByRuleIdAndStatus(Long ruleId, AlertEvent.Status status);

    long countByStatus(AlertEvent.Status status);

    @Query("SELECT e FROM AlertEvent e WHERE (:status IS NULL OR e.status = :status) "
            + "AND (:deviceId IS NULL OR e.deviceId = :deviceId) ORDER BY e.id DESC")
    org.springframework.data.domain.Page<AlertEvent> search(
            @Param("status") AlertEvent.Status status,
            @Param("deviceId") Long deviceId,
            Pageable pageable);

    /** Rule deleted -> its FIRING events become RESOLVED (spec 5.5 #11). */
    @Modifying
    @Query("UPDATE AlertEvent e SET e.status = 'RESOLVED', e.resolvedAt = :at "
            + "WHERE e.ruleId = :ruleId AND e.status = 'FIRING'")
    int resolveAllFiringOfRule(@Param("ruleId") Long ruleId, @Param("at") Instant at);
}
