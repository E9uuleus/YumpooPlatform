package com.yumpoo.platform.catalog.api;

import java.util.Objects;
import java.util.UUID;

public record ProjectFactWriteSnapshot(
        UUID projectId,
        UUID companyId,
        String projectCode,
        ProjectLifecycle lifecycle,
        ActorProjectAccess actorAccess) {
    public ProjectFactWriteSnapshot {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectCode, "projectCode must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(actorAccess, "actorAccess must not be null");
    }

    public enum ProjectLifecycle { ACTIVE, ARCHIVED }
    public enum ActorProjectAccess { MEMBER, OWNER, COMPANY_ADMIN_READ_ONLY }
}
