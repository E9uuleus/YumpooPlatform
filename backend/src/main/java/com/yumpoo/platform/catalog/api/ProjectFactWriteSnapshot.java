package com.yumpoo.platform.catalog.api;

import java.util.Objects;
import java.util.UUID;

public record ProjectFactWriteSnapshot(
        UUID projectId,
        UUID companyId,
        String projectCode,
        ProjectLifecycle lifecycle,
        ActorProjectAccess actorAccess,
        String templateKey,
        int templateVersion
) {
    public ProjectFactWriteSnapshot {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectCode, "projectCode must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(actorAccess, "actorAccess must not be null");
        Objects.requireNonNull(templateKey, "templateKey must not be null");
        if (templateVersion < 1) throw new IllegalArgumentException("templateVersion must be positive");
    }

    public enum ProjectLifecycle { DRAFT, ACTIVE, ARCHIVED }
    public enum ActorProjectAccess { MEMBER, OWNER, COMPANY_ADMIN_READ_ONLY }
}
