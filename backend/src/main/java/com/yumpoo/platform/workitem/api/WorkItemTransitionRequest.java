package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WorkItemTransitionRequest(
        @JsonProperty(required = true) @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String toStatus,
        @Size(max = 500) String resolution
) {}
