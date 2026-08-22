package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ContentCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String code,
        @NotBlank @Size(max = 80) String name,
        @Size(min = 1, max = 500) String description,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String blueprintCode
) {}
