package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachmentRescanRequest(@NotBlank @Size(max = 500) String reason) {}
