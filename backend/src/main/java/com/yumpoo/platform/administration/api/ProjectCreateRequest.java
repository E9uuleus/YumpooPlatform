package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCreateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description) {
    public ProjectCreateRequest {
        name = name == null ? null : name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
