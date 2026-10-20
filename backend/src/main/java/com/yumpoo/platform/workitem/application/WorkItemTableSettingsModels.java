package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkItemTableSettingsModels {
    private WorkItemTableSettingsModels() {}

    public enum Height { SINGLE, DOUBLE, TRIPLE }
    public enum ColoringTarget { CELL, ROW }
    public enum ColoringColumn { TITLE, ASSIGNEE, STATUS, PRIORITY, CONTENT, DUE_DATE }
    public enum ColoringOperator { IS, IS_NOT, IS_EMPTY, IS_NOT_EMPTY, CONTAINS, NOT_CONTAINS, PAST, TODAY, BEFORE, AFTER }

    public enum GroupingField { ASSIGNEE, STATUS, PRIORITY, CONTENT, DUE_DATE }
    public enum GroupingOrder { DEFAULT, REVERSE_DEFAULT, NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC }

    public record ColoringRule(UUID id, ColoringTarget target, String colorToken, ColoringColumn column,
            ColoringOperator operator, List<String> values) {}

    public record DefaultValues(List<UUID> assigneeUserIds, String statusCode, String priority, UUID contentId,
            Integer dueDateOffsetDays) {
        static DefaultValues empty() { return new DefaultValues(List.of(), null, null, null, null); }
    }

    /** 分组偏好；field 为 null 表示不分组，collapsed 为“字段:分组键”，colors 为处理人分组的颜色代码。 */
    public record Grouping(GroupingField field, GroupingOrder order, boolean showEmpty, List<String> collapsed,
            Map<String, String> colors) {}

    /**
     * PUT 请求体。前五项保存在 jsonb 中，grouping 与 completedStatusCodes 保存在独立列，旧版本读取 jsonb 时不会遇到新字段；
     * 两者省略或为 null 时保留原值。completedStatusCodes 为 null 表示按 DONE 与 CANCELED 两类状态判定完成。
     */
    public record Write(Integer pinnedColumnCount, Height headerHeight, Height rowHeight,
            List<ColoringRule> coloringRules, DefaultValues defaultValues, Grouping grouping, List<String> completedStatusCodes) {
        static Write defaults() { return new Write(0, Height.SINGLE, Height.SINGLE, List.of(), DefaultValues.empty(), null, null); }
    }

    public record View(UUID projectId, int pinnedColumnCount, Height headerHeight, Height rowHeight,
            List<ColoringRule> coloringRules, DefaultValues defaultValues, Grouping grouping, List<String> completedStatusCodes,
            Instant updatedAt, String etag) {
        static View of(UUID projectId, Write settings, Instant updatedAt) {
            long version = updatedAt == null ? 0 : updatedAt.getEpochSecond() * 1_000_000 + updatedAt.getNano() / 1_000;
            return new View(projectId, settings.pinnedColumnCount(), settings.headerHeight(), settings.rowHeight(),
                    settings.coloringRules(), settings.defaultValues(), settings.grouping(), settings.completedStatusCodes(),
                    updatedAt, StrongEtag.format(version));
        }
    }
}
