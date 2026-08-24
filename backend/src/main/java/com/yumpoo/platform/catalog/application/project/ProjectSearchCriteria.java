package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectType;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectSearchCriteria(
        String query,
        List<ProjectType> projectTypes,
        List<UUID> ownerUserIds,
        List<ProjectActorAccess> actorAccesses,
        Instant updatedSince,
        ProjectLifecycleFilter lifecycle,
        UUID productId
) {
    public ProjectSearchCriteria {
        query = query == null || query.isBlank() ? null : query.strip();
        if (query != null && query.length() > 80) {
            throw ApplicationException.validation(new FieldViolation(
                    "query", "SIZE", "搜索内容不能超过 80 个字符"));
        }
        projectTypes = projectTypes == null ? List.of() : List.copyOf(projectTypes);
        ownerUserIds = ownerUserIds == null ? List.of() : List.copyOf(ownerUserIds);
        actorAccesses = actorAccesses == null ? List.of() : List.copyOf(actorAccesses);
    }
}
