package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectArchiveCommand(
        UUID companyId,
        UUID projectId,
        long expectedRowVersion,
        UUID actorUserId,
        boolean ownerRequired
) {}
