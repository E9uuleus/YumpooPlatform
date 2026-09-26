package com.yumpoo.platform.identityaccess.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PlatformRoleChangeRequest(@NotNull PlatformRoleTier role,
        @NotBlank @Size(max = 160) String reason) {}
