package com.yumpoo.platform.catalog.api;

import java.util.Objects;
import java.util.UUID;

public record ProductSnapshot(
        UUID productId,
        UUID companyId,
        String code,
        String name,
        String description,
        ProductLifecycleStatus status,
        UUID ownerUserId,
        long rowVersion
) {
    public ProductSnapshot {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
    }
}
