package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.workitem.api.InitialStructureColors;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCreateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        InitialStructureColors initialColors) {
    public ProjectCreateRequest {
        name = name == null ? null : name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        initialColors = initialColors == null ? InitialStructureColors.DEFAULT : initialColors;
    }
}
