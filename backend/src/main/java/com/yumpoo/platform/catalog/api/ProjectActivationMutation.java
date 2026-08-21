package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectActivationMutation(
        UUID companyId,
        UUID projectId,
        long expectedRowVersion,
        UUID actorUserId
) {
}
