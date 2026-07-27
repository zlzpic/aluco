package com.aluco.server.alerting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body of POST /api/v1/rules. deviceKey nullable = applies to all devices. */
public record CreateRuleRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String metric,
        @NotNull AlertRule.Op op,
        @NotNull Double threshold,
        @Size(max = 64) String deviceKey) {}
