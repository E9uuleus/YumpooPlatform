package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ContentUpdateRequest(
        @Size(min = 1, max = 80) String name,
        @Size(min = 2, max = 24) String colorToken,
        Boolean active,
        @Positive Integer sortOrder
) {}
