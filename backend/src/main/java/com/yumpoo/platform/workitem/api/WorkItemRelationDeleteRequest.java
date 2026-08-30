package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkItemRelationDeleteRequest(
        @NotBlank @Size(max = 500) String reason) {}
