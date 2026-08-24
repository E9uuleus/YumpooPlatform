package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProjectWorkspaceMoveRequest(
        @NotNull UUID targetWorkspaceId,
        @NotNull @Size(min = 10, max = 500) String reason
) {
}
