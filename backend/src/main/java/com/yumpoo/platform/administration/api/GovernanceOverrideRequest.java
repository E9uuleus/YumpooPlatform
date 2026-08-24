package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.GovernanceOverrideAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.util.UUID;

public record GovernanceOverrideRequest(
        @NotNull GovernanceOverrideAction action,
        @NotBlank String targetType,
        @NotNull UUID targetId,
        @NotNull @Size(min = 10, max = 500) String reason
) {
    @AssertTrue(message = "only project archive overrides can be created")
    public boolean isSupportedAction() {
        return action == GovernanceOverrideAction.PROJECT_ARCHIVE_WITH_OPEN_ITEMS;
    }
}
