package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContentCreateRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 24) String colorToken
) {}
