package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectWorkspaceMoveCommand(
        UUID companyId,
        UUID projectId,
        UUID targetWorkspaceId,
        long expectedRowVersion,
        UUID actorUserId
) {}
