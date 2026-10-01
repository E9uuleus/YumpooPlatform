package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectCreateCommand(
        UUID companyId,
        String name,
        String description,
        UUID actorUserId
) {
}
