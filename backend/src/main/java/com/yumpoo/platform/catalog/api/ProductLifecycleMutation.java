package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProductLifecycleMutation(
        UUID companyId,
        UUID productId,
        long expectedRowVersion,
        UUID actorUserId
) {
}
