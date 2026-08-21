package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectWorkspaceMoveMutation(
        UUID companyId,
        UUID projectId,
        UUID targetWorkspaceId,
        long expectedRowVersion,
        UUID actorUserId
) {}
