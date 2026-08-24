package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkItemDeleteRequest(
        @JsonProperty(required = true) @NotBlank @Size(max = 500) String reason
) {}
