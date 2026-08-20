package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProductOwnerReassignmentMutation(
        UUID companyId,
        UUID productId,
        long expectedRowVersion,
        UUID newOwnerUserId,
        UUID actorUserId
) {
}
