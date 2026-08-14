package com.yumpoo.platform.identityaccess.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RoleGrantRequest(
        @NotNull UUID userId,
        @NotBlank @Size(max = 160) String reason
) {
}
