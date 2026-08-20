package com.yumpoo.platform.catalog.application.product;

import java.util.UUID;

public record ProductLifecycleChange(
        UUID companyId,
        UUID productId,
        long expectedRowVersion,
        UUID actorUserId
) {
}
