package com.yumpoo.platform.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectUpdateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @Size(max = 160) String customerName,
        @Size(max = 80) String customerReference,
        @Size(max = 160) String deliverySite,
        @Size(max = 500) String contactNote
) {
}
