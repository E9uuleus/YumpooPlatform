package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectSearchCriteria(
        String query,
        List<UUID> ownerUserIds,
        List<ProjectActorAccess> actorAccesses,
        Instant updatedSince,
        ProjectLifecycleFilter lifecycle,
        Boolean ended,
        List<UUID> ids
) {
    public static final int MAX_IDS = 50;

    public ProjectSearchCriteria(String query, List<UUID> ownerUserIds, List<ProjectActorAccess> actorAccesses,
            Instant updatedSince, ProjectLifecycleFilter lifecycle) {
        this(query, ownerUserIds, actorAccesses, updatedSince, lifecycle, null, null);
    }

    public ProjectSearchCriteria(String query, List<UUID> ownerUserIds, List<ProjectActorAccess> actorAccesses,
            Instant updatedSince, ProjectLifecycleFilter lifecycle, Boolean ended) {
        this(query, ownerUserIds, actorAccesses, updatedSince, lifecycle, ended, null);
    }

    public ProjectSearchCriteria {
        query = query == null || query.isBlank() ? null : query.strip();
        if (query != null && query.length() > 80) {
            throw ApplicationException.validation(new FieldViolation(
                    "query", "SIZE", "搜索内容不能超过 80 个字符"));
        }
        ownerUserIds = ownerUserIds == null ? List.of() : List.copyOf(ownerUserIds);
        lifecycle = lifecycle == null ? ProjectLifecycleFilter.ALL : lifecycle;
        actorAccesses = actorAccesses == null ? List.of() : List.copyOf(actorAccesses);
        ids = ids == null ? List.of() : List.copyOf(ids);
        if (ids.size() > MAX_IDS) {
            throw ApplicationException.validation(new FieldViolation(
                    "ids", "SIZE", "最多按 " + MAX_IDS + " 个项目筛选"));
        }
    }
}
