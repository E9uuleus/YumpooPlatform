package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectRestoreMutation(
        UUID companyId,
        UUID projectId,
        long expectedRowVersion,
        UUID actorUserId
) {}
