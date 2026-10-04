package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.domain.WorkItemConnection;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;

public interface WorkItemConnectionRepository {
    record ConnectionRow(WorkItemConnection connection, String columnName, boolean columnActive, long incomingTotal) {}
    record CardRow(UUID workItemId, UUID projectId, String itemNo, String title, boolean archived,
            ConnectionCardStatus status, ConnectionCardLabel priority, ConnectionCardCategory category,
            UUID assigneeUserId) {}
    record CandidateRow(UUID workItemId, Parent parent, boolean alreadyConnected) {}
    /** One incoming row within its column window; {@code cellPosition} ranks it across every column of the target item. */
    record IncomingCellRow(ConnectionRow row, long columnTotal, long cellPosition) {}
    /**
     * Searches one project's items for a connect column. The anchor is the row the picker was opened from: the source
     * item when searching targets, or the target item when searching the column's own project from the reverse column.
     */
    record CandidateQuery(UUID projectId, UUID columnId, UUID anchorWorkItemId, boolean anchorIsSource, String text,
            Set<CandidateField> fields, Collection<UUID> assigneeIds, CandidateSort sort) {}

    Optional<ConnectionRow> find(UUID companyId, UUID connectionId);
    Optional<ConnectionRow> findReadable(UUID companyId, UUID connectionId, Set<UUID> visibleProjectIds);
    Optional<WorkItemConnection> lock(UUID companyId, UUID connectionId);
    Optional<WorkItemConnection> findActivePair(UUID companyId, UUID columnId, UUID sourceId, UUID targetId);
    boolean insert(WorkItemConnection connection);
    boolean softDelete(WorkItemConnection connection, long expectedVersion);
    long deleteColumnConnections(UUID companyId, UUID columnId, UUID actorId, Instant now);
    long countActiveCell(UUID companyId, UUID columnId, UUID sourceId);
    long countActiveTarget(UUID companyId, UUID columnId, UUID targetProjectId);
    List<UUID> findActiveProjectItems(UUID companyId, UUID projectId, Collection<UUID> itemIds);
    List<ConnectionRow> findOutgoing(UUID companyId, UUID projectId, Collection<UUID> itemIds);
    List<IncomingCellRow> findIncomingCells(UUID companyId, UUID projectId, Collection<UUID> itemIds);
    Set<UUID> findIncomingProjectIds(UUID companyId, UUID workItemId);
    List<ConnectionRow> findIncomingPage(UUID companyId, UUID workItemId, UUID columnId, Set<UUID> visibleProjectIds,
            OffsetPageRequest page);
    long countIncoming(UUID companyId, UUID workItemId, UUID columnId, Set<UUID> visibleProjectIds);
    List<CardRow> findCards(UUID companyId, Collection<UUID> itemIds, boolean includingDeleted);
    List<ConnectionCardCategory> findActiveCategories(UUID companyId, UUID projectId);
    List<CandidateRow> findCandidates(UUID companyId, CandidateQuery query, OffsetPageRequest page);
    long countCandidates(UUID companyId, CandidateQuery query);
}
