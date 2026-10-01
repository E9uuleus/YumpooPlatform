package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectCreationMutation(
        UUID companyId,
        String name,
        String description,
        UUID actorUserId
) {
}
