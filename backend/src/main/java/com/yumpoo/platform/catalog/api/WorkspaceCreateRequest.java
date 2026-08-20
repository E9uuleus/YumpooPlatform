package com.yumpoo.platform.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record WorkspaceCreateRequest(
        @NotBlank
        @Size(min = 2, max = 32)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$")
        String code,
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @PositiveOrZero int sortOrder
) {
}
