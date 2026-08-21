package com.yumpoo.platform.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ProjectProductLinkCreateRequest(
        @NotNull UUID productId,
        @NotBlank String relationType,
        boolean isPrimary
) {
}
