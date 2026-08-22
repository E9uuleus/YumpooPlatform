package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectRestoreCommand(
        UUID companyId,
        UUID projectId,
        long expectedRowVersion,
        UUID actorUserId
) {}
