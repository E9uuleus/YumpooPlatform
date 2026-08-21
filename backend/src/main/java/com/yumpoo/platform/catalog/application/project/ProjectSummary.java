package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectSummary(
        UUID id,
        UUID workspaceId,
        String workspaceCode,
        String workspaceName,
        String code,
        String name,
        String projectType,
        String lifecycle,
        UUID ownerUserId,
        String ownerDisplayName,
        ProjectActorAccess actorAccess,
        ProjectCapabilities capabilities,
        long rowVersion,
        String etag
) {
}
