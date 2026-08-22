package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record ContentUpdateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(min = 1, max = 500) String description,
        @NotBlank String defaultViewType,
        @NotNull JsonNode viewConfig
) {}
