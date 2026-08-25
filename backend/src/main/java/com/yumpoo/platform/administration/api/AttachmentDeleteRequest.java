package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachmentDeleteRequest(
        @NotBlank @Size(max = 500) String reason
) {}
