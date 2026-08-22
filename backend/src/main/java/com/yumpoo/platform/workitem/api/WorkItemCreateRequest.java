package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WorkItemCreateRequest(
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority,
        @Size(max = 16384) String description,
        @Size(max = 16384) String notes
) {}
