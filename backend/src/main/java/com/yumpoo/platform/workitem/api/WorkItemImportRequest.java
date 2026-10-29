package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.workitem.application.WorkItemImportModels.DuplicateMode;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Field;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WorkItemImportRequest(
        @NotNull Boolean dryRun,
        @NotNull DuplicateMode duplicateMode,
        @NotNull List<@NotNull Field> fields,
        @NotNull @Size(min = 1, max = 2000) List<@NotNull @Valid Row> rows
) {
    public record Row(
            @NotNull @Min(1) Integer rowNumber,
            @NotNull @Size(max = 1000) String title,
            @NotNull UUID contentId,
            @NotNull @Size(max = 32) String statusCode,
            @Size(max = 32) String priority,
            @NotNull @Size(max = 20) List<@NotNull UUID> assigneeUserIds,
            LocalDate dueDate,
            @Size(max = 1000) String parentTitle,
            @Size(max = 16384) String notes
    ) {}
}
