package com.aluco.server.device;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceKey(String deviceKey);

    boolean existsByDeviceKey(String deviceKey);

    void deleteByDeviceKey(String deviceKey);

    @Query("SELECT d FROM Device d WHERE :kw IS NULL "
            + "OR d.deviceKey LIKE CONCAT('%', :kw, '%') "
            + "OR d.name LIKE CONCAT('%', :kw, '%')")
    org.springframework.data.domain.Page<Device> search(@Param("kw") String keyword, Pageable pageable);

    @Query("SELECT d.deviceKey FROM Device d")
    List<String> findAllDeviceKeys();
}
