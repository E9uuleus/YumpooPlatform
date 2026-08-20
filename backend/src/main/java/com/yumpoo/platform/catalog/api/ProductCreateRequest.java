package com.yumpoo.platform.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProductCreateRequest(
        @NotBlank @Size(min = 2, max = 32)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String code,
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull UUID ownerUserId
) {
}
