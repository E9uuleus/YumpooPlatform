package com.yumpoo.platform.workitem.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record WorkItemRankMoveRequest(
        @JsonProperty(required = true) @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String toStatus,
        @JsonProperty(required = true) @NotNull WorkItemRankPlacement placement,
        UUID anchorWorkItemId,
        @Size(max = 500) String resolution
) {}
