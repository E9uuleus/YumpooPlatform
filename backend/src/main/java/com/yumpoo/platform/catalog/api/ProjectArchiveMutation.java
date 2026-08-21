package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectArchiveMutation(
        UUID companyId,
        UUID projectId,
        long expectedRowVersion,
        UUID actorUserId,
        boolean ownerRequired
) {}
