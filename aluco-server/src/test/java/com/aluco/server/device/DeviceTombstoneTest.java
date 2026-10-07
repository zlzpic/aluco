package com.aluco.server.device;

import com.aluco.server.auth.EmqxAuthService;
import com.aluco.server.common.DeviceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test tombstone behavior for DeviceServiceImpl.delete() (v2 spec 4.4.1).
 */
@ExtendWith(MockitoExtension.class)
class DeviceTombstoneTest {

    @Mock
    DeviceRepository deviceRepository;

    @Mock
    StateStore stateStore;

    @Mock
    DeviceTombstoneRepository tombstoneRepository;

    @Mock
    EmqxAuthService emqxAuthService;

    @Mock
    MySqlStateStore mySqlStateStore;

    @InjectMocks
    DeviceServiceImpl deviceService;

    @Test
    void delete_writesTombstoneBeforeDeletingDevice() {
        // Arrange
        Device device = new Device("TH-0001", "Sensor 1", "site-01", "token123");

        when(deviceRepository.findByDeviceKey("TH-0001")).thenReturn(Optional.of(device));
        when(tombstoneRepository.save(any())).thenReturn(new DeviceTombstone(1L, "TH-0001", Instant.now()));

        // Act
        deviceService.delete("TH-0001");

        // Assert: tombstone is saved
        verify(tombstoneRepository, times(1)).save(any(DeviceTombstone.class));

        // Assert: device is deleted
        verify(deviceRepository, times(1)).delete(device);
    }

    @Test
    void delete_revokesEmqxCredentials() {
        // Arrange
        Device device = new Device("TH-0001", "Sensor 1", "site-01", "token123");

        when(deviceRepository.findByDeviceKey("TH-0001")).thenReturn(Optional.of(device));
        when(tombstoneRepository.save(any())).thenReturn(new DeviceTombstone(1L, "TH-0001", Instant.now()));

        // Act
        deviceService.delete("TH-0001");

        // Assert: EMQX credentials are revoked
        verify(emqxAuthService, times(1)).removeDeviceCredential("TH-0001");
    }

    @Test
    void delete_continues_whenEmqxRevocationFails() {
        // Arrange: EMQX revocation throws exception
        Device device = new Device("TH-0001", "Sensor 1", "site-01", "token123");

        when(deviceRepository.findByDeviceKey("TH-0001")).thenReturn(Optional.of(device));
        when(tombstoneRepository.save(any())).thenReturn(new DeviceTombstone(1L, "TH-0001", Instant.now()));
        doThrow(new RuntimeException("EMQX unreachable")).when(emqxAuthService).removeDeviceCredential(any());

        // Act: should NOT throw
        deviceService.delete("TH-0001");

        // Assert: device is still deleted
        verify(deviceRepository, times(1)).delete(device);
    }

    @Test
    void getState_returnsTombstonedState_whenDeviceDeleted() {
        // Arrange: device not in device table, but exists in tombstone
        DeviceTombstone tombstone = new DeviceTombstone(1L, "TH-0001", Instant.now());

        when(deviceRepository.findByDeviceKey("TH-0001")).thenReturn(Optional.empty());
        when(tombstoneRepository.findByDeviceKey("TH-0001")).thenReturn(Optional.of(tombstone));

        // Act
        DeviceState state = deviceService.getState("TH-0001");

        // Assert: returns tombstoned state (online=false, empty metrics)
        assertThat(state.deviceKey()).isEqualTo("TH-0001");
        assertThat(state.online()).isFalse();
        assertThat(state.metrics()).isEmpty();
    }
}
