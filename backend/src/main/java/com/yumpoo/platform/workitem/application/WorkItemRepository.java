package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.WorkItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WorkItemRepository {
    record RankedWorkItem(UUID id, String rank) {}
    record RankedProjectWorkItem(UUID id, String rank) {}
    record FilterOptionCount(String value, long count) {}
    record ProjectCursorAnchor(UUID id, String projectSortKey, long itemSequence,
            String title, String statusCode, String priority,
            UUID assigneeUserId, UUID reporterUserId, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate, Instant updatedAt) {
        static ProjectCursorAnchor from(WorkItem item) {
            return new ProjectCursorAnchor(item.id(), item.projectSortKey(), item.itemSequence(),
                    item.title(), item.statusCode(), item.priority(), item.assigneeUserId(),
                    item.reporterUserId(), item.timelineStartDate(), item.timelineEndDate(),
                    item.dueDate(), item.updatedAt());
        }
    }

    long nextSequence(UUID companyId, UUID projectId);
    boolean insert(WorkItem workItem);
    Optional<WorkItemModels.WorkItemLocator> findLocator(UUID companyId, UUID workItemId);
    Optional<WorkItemModels.WorkItemLocator> findLocator(UUID companyId, UUID projectId,
            UUID workItemId);
    Optional<WorkItemModels.WorkItemLocator> findLocatorIncludingDeleted(UUID companyId,
            UUID workItemId);
    Optional<WorkItem> find(UUID companyId, UUID projectId, UUID contentId, UUID workItemId);
    Optional<WorkItem> findIncludingDeleted(UUID companyId, UUID projectId, UUID contentId,
            UUID workItemId);
    Optional<WorkItem> lock(UUID companyId, UUID projectId, UUID contentId, UUID workItemId);
    Optional<WorkItem> lockIncludingDeleted(UUID companyId, UUID projectId, UUID contentId,
            UUID workItemId);
    Optional<WorkItem> update(WorkItem workItem, long expectedVersion);
    Optional<WorkItem> transition(WorkItem workItem, long expectedVersion);
    Optional<WorkItem> softDelete(WorkItem workItem, long expectedVersion);
    Optional<WorkItem> restore(WorkItem workItem, long expectedVersion);
    void lockRankLanes(UUID contentId, Collection<String> statuses);
    List<RankedWorkItem> findRankOrder(UUID companyId, UUID projectId, UUID contentId,
            String statusCode);
    void rewriteRanks(UUID companyId, UUID projectId, UUID contentId, String statusCode,
            Map<UUID, String> ranks);
    void lockProjectOrder(UUID companyId, UUID projectId);
    Optional<WorkItem> lockProjectItem(UUID companyId, UUID projectId, UUID workItemId);
    Optional<RankedProjectWorkItem> findProjectNeighborBefore(UUID companyId, UUID projectId,
            String projectSortKey, UUID excludedId);
    Optional<RankedProjectWorkItem> findProjectNeighborAfter(UUID companyId, UUID projectId,
            String projectSortKey, UUID excludedId);
    Optional<RankedProjectWorkItem> findFirstProjectRank(UUID companyId, UUID projectId,
            UUID excludedId);
    boolean projectSortKeyOccupied(UUID companyId, UUID projectId, String projectSortKey,
            UUID excludedId);
    List<RankedProjectWorkItem> findProjectRankWindow(UUID companyId, UUID projectId,
            String pivotKey, UUID excludedId, int limit);
    void rewriteProjectSortKeys(UUID companyId, UUID projectId, Map<UUID, String> ranks);
    Optional<WorkItem> reorderProject(WorkItem workItem, long expectedVersion);
    Set<UUID> findParticipantUserIds(UUID companyId, UUID projectId, UUID contentId);
    Set<UUID> findProjectParticipantUserIds(UUID companyId, UUID projectId);
    List<WorkItem> findPage(UUID companyId, UUID projectId, UUID contentId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            OffsetPageRequest page);
    long countPage(UUID companyId, UUID projectId, UUID contentId, WorkItemQuery query);
    List<WorkItem> findProjectPage(UUID companyId, UUID projectId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            OffsetPageRequest page);
    List<WorkItem> findProjectCursorPage(UUID companyId, UUID projectId,
            WorkItemQuery query, WorkItemSortRanks ranks, ContentViewType view,
            ProjectCursorAnchor anchor, int limit);
    List<WorkItem> findSubitems(UUID companyId, UUID projectId, UUID parentWorkItemId,
            WorkItemQuery query, WorkItemSortRanks ranks);
    List<FilterOptionCount> findProjectFilterOptions(UUID companyId, UUID projectId,
            WorkItemQuery query, String field, String afterValue, int limit);
    long countProjectPage(UUID companyId, UUID projectId, WorkItemQuery query);
    long countOpenByProject(UUID companyId, UUID projectId);
    long countOpenByContent(UUID companyId, UUID projectId, UUID contentId);
}
