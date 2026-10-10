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
        String lifecycle,
        UUID ownerUserId,
        String ownerDisplayName,
        ProjectActorAccess actorAccess,
        ProjectCapabilities capabilities,
        long rowVersion,
        String etag,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ProjectDeletion deletion
) {
    public ProjectDetail(UUID id,UUID workspaceId,String workspaceCode,String workspaceName,String code,String name,
            String description,String lifecycle,UUID ownerUserId,String ownerDisplayName,ProjectActorAccess access,
            ProjectCapabilities capabilities,long rowVersion,String etag,Instant createdAt,Instant updatedAt,Instant archivedAt) {
        this(id,workspaceId,workspaceCode,workspaceName,code,name,description,lifecycle,ownerUserId,ownerDisplayName,
                access,capabilities,rowVersion,etag,createdAt,updatedAt,archivedAt,null);
    }
}
