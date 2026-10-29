package com.yumpoo.platform.workitem.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WorkItemImportModels {
    private WorkItemImportModels() {}

    public enum DuplicateMode { CREATE, SKIP, UPDATE }

    public enum Field { CONTENT, STATUS, PRIORITY, ASSIGNEES, DUE_DATE, PARENT, NOTES }

    public enum Outcome { CREATE, UPDATE, SKIP, ERROR }

    public record Row(int rowNumber, String title, UUID contentId, String statusCode,
            String priority, List<UUID> assigneeUserIds, LocalDate dueDate, String parentTitle,
            String notes) {
        public Row {
            assigneeUserIds = assigneeUserIds == null ? List.of() : List.copyOf(assigneeUserIds);
        }
    }

    public record Command(CurrentActor actor, UUID projectId, boolean dryRun,
            DuplicateMode duplicateMode, Set<Field> fields, List<Row> rows, UUID idempotencyKey,
            RequestHash requestHash) {}

    public record Issue(String code,
            @JsonInclude(JsonInclude.Include.ALWAYS) String detail) {}

    public record RowResult(int rowNumber, Outcome outcome,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID workItemId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String itemNo, List<Issue> issues) {}

    public record Result(boolean dryRun, int createdCount, int updatedCount, int skippedCount,
            int failedCount, List<RowResult> rows) {}
}
