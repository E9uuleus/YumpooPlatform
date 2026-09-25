package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

public record WorkItemDescriptionPatchRequest(
        @JsonProperty(required = true) @Size(max = 65536) String description
) {}
