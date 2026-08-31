package com.yumpoo.platform.catalog.api;

import java.util.Objects;
import java.util.UUID;

public record ProductFactWriteSnapshot(
        UUID productId,
        UUID companyId,
        String code,
        ProductLifecycleStatus status,
        UUID ownerUserId
) {
    public ProductFactWriteSnapshot {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
    }
}
