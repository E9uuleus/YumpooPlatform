package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.WorkItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WorkItemRepository {
    record RankedWorkItem(UUID id, String rank) {}

    long nextSequence(UUID companyId, UUID projectId);
    boolean insert(WorkItem workItem);
    Optional<WorkItemModels.WorkItemLocator> findLocator(UUID companyId, UUID workItemId);
    Optional<WorkItem> find(UUID companyId, UUID projectId, UUID contentId, UUID workItemId);
    Optional<WorkItem> lock(UUID companyId, UUID projectId, UUID contentId, UUID workItemId);
    Optional<WorkItem> update(WorkItem workItem, long expectedVersion);
    Optional<WorkItem> transition(WorkItem workItem, long expectedVersion);
    void lockRankLanes(UUID contentId, Collection<String> statuses);
    List<RankedWorkItem> findRankOrder(UUID companyId, UUID projectId, UUID contentId,
            String statusCode);
    void rewriteRanks(UUID companyId, UUID projectId, UUID contentId, String statusCode,
            Map<UUID, String> ranks);
    Set<UUID> findParticipantUserIds(UUID companyId, UUID projectId, UUID contentId);
    List<WorkItem> findPage(UUID companyId, UUID projectId, UUID contentId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            OffsetPageRequest page);
    long countPage(UUID companyId, UUID projectId, UUID contentId, WorkItemQuery query);
    long countOpenByProject(UUID companyId, UUID projectId);
    long countOpenByContent(UUID companyId, UUID projectId, UUID contentId);
}
