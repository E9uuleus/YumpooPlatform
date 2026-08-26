package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record WorkItem(
        UUID id, UUID companyId, UUID projectId, UUID contentId,
        long itemSequence, String itemNo, ContentWorkItemType type,
        String title, String statusCode, WorkItemStatusCategory statusCategory,
        WorkItemPriority priority, UUID assigneeUserId, UUID reporterUserId,
        String description, String notes, LocalDate timelineStartDate,
        LocalDate timelineEndDate, LocalDate dueDate, String rank, String projectSortKey,
        long rowVersion,
        Instant createdAt, UUID createdByUserId, Instant updatedAt,
        UUID updatedByUserId, Instant deletedAt, UUID deletedByUserId, String deleteReason
) {
    private static final Pattern ITEM_NO = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}-[1-9][0-9]*$");
    private static final Pattern STATUS = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    private static final int MAX_BODY_LENGTH = 16_384;

    public WorkItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(contentId, "contentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(statusCategory, "statusCategory must not be null");
        Objects.requireNonNull(reporterUserId, "reporterUserId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        if (itemSequence < 1 || rowVersion < 0 || updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("work item sequence, version or timestamps are invalid");
        if (itemNo == null || !ITEM_NO.matcher(itemNo).matches())
            throw new IllegalArgumentException("itemNo must contain project code and sequence");
        title = normalizeRequired(title, 300, "title");
        if (statusCode == null || !STATUS.matcher(statusCode).matches())
            throw new IllegalArgumentException("statusCode must be a stable uppercase identifier");
        description = normalizeOptional(description, MAX_BODY_LENGTH, "description");
        notes = normalizeOptional(notes, MAX_BODY_LENGTH, "notes");
        if (timelineStartDate != null && timelineEndDate != null
                && timelineEndDate.isBefore(timelineStartDate))
            throw new IllegalArgumentException("timeline end must not precede start");
        rank = KanbanRank.require(rank);
        projectSortKey = ProjectSortKey.require(projectSortKey);
        boolean deleted = deletedAt != null || deletedByUserId != null || deleteReason != null;
        if (deleted && (deletedAt == null || deletedByUserId == null || deleteReason == null))
            throw new IllegalArgumentException("deleted work item must contain complete delete facts");
        if (deleted) {
            deleteReason = normalizeRequired(deleteReason, 500, "deleteReason");
            if (deletedAt.isBefore(createdAt))
                throw new IllegalArgumentException("deletedAt must not precede createdAt");
        }
    }

    public static WorkItem create(UUID id, UUID companyId, UUID projectId, UUID contentId,
            long itemSequence, String itemNo, ContentWorkItemType type, String title,
            String statusCode, WorkItemStatusCategory statusCategory, WorkItemPriority priority,
            UUID assigneeUserId, String description, String notes, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate, String rank,
            UUID reporterUserId, Instant now) {
        return create(id, companyId, projectId, contentId, itemSequence, itemNo, type, title,
                statusCode, statusCategory, priority, assigneeUserId, description, notes,
                timelineStartDate, timelineEndDate, dueDate, rank,
                ProjectSortKey.evenlySpaced(1, 1), reporterUserId, now);
    }

    public static WorkItem create(UUID id, UUID companyId, UUID projectId, UUID contentId,
            long itemSequence, String itemNo, ContentWorkItemType type, String title,
            String statusCode, WorkItemStatusCategory statusCategory, WorkItemPriority priority,
            UUID assigneeUserId, String description, String notes, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate, String rank, String projectSortKey,
            UUID reporterUserId, Instant now) {
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, statusCode, statusCategory, priority, assigneeUserId, reporterUserId,
                description, notes, timelineStartDate, timelineEndDate, dueDate, rank,
                projectSortKey, 0, now, reporterUserId, now, reporterUserId, null, null, null);
    }

    public WorkItem updateFields(String nextTitle, WorkItemPriority nextPriority,
            UUID nextAssigneeUserId, String nextDescription, String nextNotes,
            LocalDate nextTimelineStartDate, LocalDate nextTimelineEndDate,
            LocalDate nextDueDate, UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("updatedAt must not move backwards");
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                nextTitle, statusCode, statusCategory, nextPriority, nextAssigneeUserId,
                reporterUserId, nextDescription, nextNotes, nextTimelineStartDate,
                nextTimelineEndDate, nextDueDate, rank, projectSortKey, rowVersion, createdAt,
                createdByUserId, now, actorUserId, deletedAt, deletedByUserId, deleteReason);
    }

    public WorkItem move(String nextStatusCode, WorkItemStatusCategory nextStatusCategory,
            String nextRank, UUID actorUserId, Instant now) {
        Objects.requireNonNull(nextStatusCategory, "nextStatusCategory must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (deletedAt != null) throw new IllegalStateException("deleted work item cannot transition");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("updatedAt must not move backwards");
        if (Objects.equals(statusCode, nextStatusCode))
            throw new IllegalArgumentException("status transition endpoints must differ");
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, nextStatusCode, nextStatusCategory, priority, assigneeUserId,
                reporterUserId, description, notes, timelineStartDate, timelineEndDate,
                dueDate, nextRank, projectSortKey, rowVersion, createdAt, createdByUserId,
                now, actorUserId,
                deletedAt, deletedByUserId, deleteReason);
    }

    public WorkItem reorder(String nextRank, UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (deletedAt != null) throw new IllegalStateException("deleted work item cannot move");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("updatedAt must not move backwards");
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, statusCode, statusCategory, priority, assigneeUserId, reporterUserId,
                description, notes, timelineStartDate, timelineEndDate, dueDate, nextRank,
                projectSortKey, rowVersion, createdAt, createdByUserId, now, actorUserId, deletedAt,
                deletedByUserId, deleteReason);
    }

    public WorkItem reorderProject(String nextProjectSortKey, UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        if (deletedAt != null) throw new IllegalStateException("deleted work item cannot move");
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, statusCode, statusCategory, priority, assigneeUserId, reporterUserId,
                description, notes, timelineStartDate, timelineEndDate, dueDate, rank,
                nextProjectSortKey, rowVersion, createdAt, createdByUserId, updatedAt,
                actorUserId, deletedAt, deletedByUserId, deleteReason);
    }

    public WorkItem softDelete(String reason, UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (deletedAt != null) throw new IllegalStateException("work item is already deleted");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("updatedAt must not move backwards");
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, statusCode, statusCategory, priority, assigneeUserId, reporterUserId,
                description, notes, timelineStartDate, timelineEndDate, dueDate, rank,
                projectSortKey, rowVersion, createdAt, createdByUserId, now, actorUserId,
                now, actorUserId, reason);
    }

    public WorkItem restore(String nextRank, UUID actorUserId, Instant now) {
        return restore(nextRank, projectSortKey, actorUserId, now);
    }

    public WorkItem restore(String nextRank, String nextProjectSortKey, UUID actorUserId,
            Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (deletedAt == null) throw new IllegalStateException("work item is not deleted");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("updatedAt must not move backwards");
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, statusCode, statusCategory, priority, assigneeUserId, reporterUserId,
                description, notes, timelineStartDate, timelineEndDate, dueDate, nextRank,
                nextProjectSortKey, rowVersion, createdAt, createdByUserId, now, actorUserId,
                null, null, null);
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    private static String normalizeRequired(String value, int maximum, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum)
            throw new IllegalArgumentException(field + " length is invalid");
        return normalized;
    }

    private static String normalizeOptional(String value, int maximum, String field) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maximum)
            throw new IllegalArgumentException(field + " length is invalid");
        return normalized;
    }
}
