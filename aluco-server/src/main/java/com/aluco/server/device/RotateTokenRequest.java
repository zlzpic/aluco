package com.aluco.server.device;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to rotate device token (v2 spec 5.2.4).
 * Old token is immediately invalidated.
 */
public record RotateTokenRequest(
        @NotBlank String deviceKey
) {}
