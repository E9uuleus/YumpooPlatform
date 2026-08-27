package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface WorkItemRelationRepository {
    record ParentChildRelation(UUID id, UUID companyId, UUID parentWorkItemId,
            UUID childWorkItemId, UUID projectId, UUID createdByUserId, Instant createdAt) {}

    boolean insertParentChild(ParentChildRelation relation);
    boolean hasActiveParent(UUID companyId, UUID workItemId);
    boolean isActiveChildOf(UUID companyId, UUID parentWorkItemId, UUID childWorkItemId);
    Map<UUID, Long> countActiveChildren(UUID companyId, Collection<UUID> parentWorkItemIds);
}
