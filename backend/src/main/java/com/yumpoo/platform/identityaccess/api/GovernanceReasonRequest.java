package com.yumpoo.platform.identityaccess.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GovernanceReasonRequest(
        @NotBlank @Size(max = 160) String reason
) {
}
