package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectEndCommand(UUID companyId, UUID projectId, long expectedRowVersion,
        UUID actorUserId, boolean admin, boolean ended) {}
