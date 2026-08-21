package com.yumpoo.platform.catalog.application.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectDetail(
        UUID id,
        UUID workspaceId,
        String workspaceCode,
        String workspaceName,
        String code,
        String name,
        String description,
        String projectType,
        String lifecycle,
        UUID ownerUserId,
        String ownerDisplayName,
        String templateKey,
        int templateVersion,
        String customerName,
        String customerReference,
        String deliverySite,
        String contactNote,
        ProjectActorAccess actorAccess,
        ProjectCapabilities capabilities,
        long rowVersion,
        String etag,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant archivedAt
) {
}
