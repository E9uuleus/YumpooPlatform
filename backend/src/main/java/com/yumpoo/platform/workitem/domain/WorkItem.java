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
        LocalDate timelineEndDate, LocalDate dueDate, String rank, long rowVersion,
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
        Objects.requireNonNull(priority, "priority must not be null");
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
        rank = normalizeOptional(rank, 128, "rank");
        boolean deleted = deletedAt != null || deletedByUserId != null || deleteReason != null;
        if (deleted && (deletedAt == null || deletedByUserId == null || deleteReason == null))
            throw new IllegalArgumentException("deleted work item must contain complete delete facts");
    }

    public static WorkItem create(UUID id, UUID companyId, UUID projectId, UUID contentId,
            long itemSequence, String itemNo, ContentWorkItemType type, String title,
            String statusCode, WorkItemStatusCategory statusCategory, WorkItemPriority priority,
            String description, String notes, UUID reporterUserId, Instant now) {
        return new WorkItem(id, companyId, projectId, contentId, itemSequence, itemNo, type,
                title, statusCode, statusCategory, priority, null, reporterUserId,
                description, notes, null, null, null, null, 0, now, reporterUserId,
                now, reporterUserId, null, null, null);
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
