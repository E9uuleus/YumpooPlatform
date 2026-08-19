package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectTemplateReasonRequest(
        @NotBlank @Size(max = 160) String reason
) {
    public ProjectTemplateReasonRequest {
        if (reason != null) {
            reason = reason.strip();
        }
    }
}
