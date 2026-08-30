package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WorkItemRelationCreateRequest(
        @NotBlank String relationType,
        @NotBlank String currentRole,
        @NotNull UUID targetWorkItemId) {}
