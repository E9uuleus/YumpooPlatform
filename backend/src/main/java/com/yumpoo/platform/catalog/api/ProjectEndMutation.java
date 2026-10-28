package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectEndMutation(UUID companyId, UUID projectId, long expectedRowVersion,
        UUID actorUserId, boolean admin, boolean ended) {}
