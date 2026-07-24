package com.aluco.server.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of POST /api/v1/devices (also bound by the controller directly). */
public record CreateDeviceRequest(
        @NotBlank @Size(max = 64) String deviceKey,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String siteId) {}
