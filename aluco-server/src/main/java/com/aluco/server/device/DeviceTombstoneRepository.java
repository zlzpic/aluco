package com.aluco.server.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeviceTombstoneRepository extends JpaRepository<DeviceTombstone, Long> {

    Optional<DeviceTombstone> findByDeviceKey(String deviceKey);

    @Query("SELECT dt FROM DeviceTombstone dt WHERE dt.deviceKey = :deviceKey")
    Optional<DeviceTombstone> findByDeviceKeyOpt(@Param("deviceKey") String deviceKey);

    boolean existsByDeviceKey(String deviceKey);
}
