package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectActivationCommand(
        UUID companyId,
        UUID projectId,
        long expectedRowVersion,
        UUID actorUserId
) {
}
