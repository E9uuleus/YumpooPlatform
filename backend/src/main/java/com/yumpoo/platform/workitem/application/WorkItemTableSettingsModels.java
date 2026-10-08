package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkItemTableSettingsModels {
    private WorkItemTableSettingsModels() {}

    public enum Height { SINGLE, DOUBLE, TRIPLE }
    public enum ColoringTarget { CELL, ROW }
    public enum ColoringColumn { TITLE, ASSIGNEE, STATUS, PRIORITY, CONTENT, DUE_DATE }
    public enum ColoringOperator { IS, IS_NOT, IS_EMPTY, IS_NOT_EMPTY, CONTAINS, NOT_CONTAINS, PAST, TODAY, BEFORE, AFTER }

    public record ColoringRule(UUID id, ColoringTarget target, String colorToken, ColoringColumn column,
            ColoringOperator operator, List<String> values) {}

    public record DefaultValues(List<UUID> assigneeUserIds, String statusCode, String priority, UUID contentId,
            Integer dueDateOffsetDays) {
        static DefaultValues empty() { return new DefaultValues(List.of(), null, null, null, null); }
    }

    /** PUT 请求体，同时也是 jsonb 中保存的内容。 */
    public record Write(Integer pinnedColumnCount, Height headerHeight, Height rowHeight,
            List<ColoringRule> coloringRules, DefaultValues defaultValues) {
        static Write defaults() { return new Write(0, Height.SINGLE, Height.SINGLE, List.of(), DefaultValues.empty()); }
    }

    public record View(UUID projectId, int pinnedColumnCount, Height headerHeight, Height rowHeight,
            List<ColoringRule> coloringRules, DefaultValues defaultValues, Instant updatedAt) {
        static View of(UUID projectId, Write settings, Instant updatedAt) {
            return new View(projectId, settings.pinnedColumnCount(), settings.headerHeight(), settings.rowHeight(),
                    settings.coloringRules(), settings.defaultValues(), updatedAt);
        }
    }
}
