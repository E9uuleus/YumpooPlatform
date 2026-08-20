package com.yumpoo.platform.catalog.application.product;

import java.util.UUID;

public record ProductOwnerChange(
        UUID companyId,
        UUID productId,
        long expectedRowVersion,
        UUID newOwnerUserId,
        UUID actorUserId
) {
}
