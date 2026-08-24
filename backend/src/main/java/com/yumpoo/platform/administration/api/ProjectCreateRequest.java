package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProjectCreateRequest(
        UUID workspaceId,
        @NotBlank @Size(min = 2, max = 32)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String code,
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotBlank
        @Pattern(regexp = "^(PRODUCT_DEVELOPMENT|PRE_SALES|IMPLEMENTATION|HYPERCARE)$")
        String projectType,
        @NotNull UUID ownerUserId,
        @NotBlank
        @Pattern(regexp = "^(RND|PRE_SALES|IMPLEMENTATION|HYPERCARE)$") String templateKey,
        @Min(1) int templateVersion,
        @Size(max = 160) String customerName,
        @Size(max = 80) String customerReference,
        @Size(max = 160) String deliverySite,
        @Size(max = 500) String contactNote
) {
}
