package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record WorkItemParentChangeRequest(
        @NotNull UUID newParentWorkItemId,
        @NotBlank @Size(max = 500) String reason) {}
