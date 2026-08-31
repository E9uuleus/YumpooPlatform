package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.domain.WorkItemRelation;
import com.yumpoo.platform.workitem.domain.WorkItemRelationType;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WorkItemRelationRepository {
    record ParentChildRelation(UUID id, UUID companyId, UUID parentWorkItemId,
            UUID childWorkItemId, UUID projectId, UUID createdByUserId, Instant createdAt) {}

    record Endpoint(UUID id, UUID projectId, UUID contentId, String itemNo, String type,
            String title, String statusCode, boolean deleted) {}

    record Projection(WorkItemRelation relation, Endpoint left, Endpoint right) {}

    record CandidateFacts(Endpoint item, boolean alreadyRelated, boolean parentIsChild,
            boolean childHasChildren, UUID activeParentRelationId, long activeParentVersion,
            Endpoint activeParentItem) {}

    boolean insertParentChild(ParentChildRelation relation);
    boolean insert(WorkItemRelation relation);
    Optional<WorkItemRelation> findById(UUID companyId, UUID relationId);
    Optional<WorkItemRelation> lock(UUID companyId, UUID relationId);
    Optional<WorkItemRelation> findActivePair(UUID companyId, WorkItemRelationType relationType,
            UUID leftWorkItemId, UUID rightWorkItemId);
    Optional<WorkItemRelation> findActiveParent(UUID companyId, UUID childWorkItemId);
    Optional<Projection> findProjection(UUID companyId, UUID relationId);
    Optional<WorkItemRelation> softDelete(WorkItemRelation relation, long expectedVersion);
    List<Projection> findActiveForWorkItem(UUID companyId, UUID workItemId,
            WorkItemRelationType relationType, OffsetPageRequest page);
    long countActiveForWorkItem(UUID companyId, UUID workItemId,
            WorkItemRelationType relationType);
    List<CandidateFacts> findCandidates(UUID companyId, UUID projectId, UUID excludedWorkItemId,
            String query, WorkItemRelationType relationType, boolean currentIsLeft,
            OffsetPageRequest page);
    long countCandidates(UUID companyId, UUID projectId, UUID excludedWorkItemId, String query);
    boolean hasActiveParent(UUID companyId, UUID workItemId);
    boolean hasActiveChildren(UUID companyId, UUID workItemId);
    boolean isActiveChildOf(UUID companyId, UUID parentWorkItemId, UUID childWorkItemId);
    Map<UUID, Long> countActiveChildren(UUID companyId, Collection<UUID> parentWorkItemIds);
}
