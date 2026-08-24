package com.yumpoo.platform.catalog.api;

import java.util.Objects;
import java.util.UUID;

public record ProjectModerationSnapshot(
        UUID projectId,
        UUID companyId,
        ProjectLifecycle lifecycle,
        ActorProjectAccess actorAccess
) {
    public ProjectModerationSnapshot {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(actorAccess, "actorAccess must not be null");
    }

    public enum ProjectLifecycle { DRAFT, ACTIVE, ARCHIVED }
    public enum ActorProjectAccess { MEMBER, OWNER, COMPANY_ADMIN_READ_ONLY }
}
