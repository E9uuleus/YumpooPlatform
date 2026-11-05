package com.yumpoo.platform.workitem.application;

import static com.yumpoo.platform.workitem.application.WorkItemModels.*;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelModels;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemViewType;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class WorkItemRules {
    static final String INVALID_WORK_ITEM_MESSAGE =
            "工作项字段不符合要求，请检查标题（1～300 字）、备注（最多 16384 字）、处理人（最多 20 位且不重复）、截止时间与时间线日期";

    private WorkItemRules() {}

    static ApplicationException invalidProjectOrderAnchor() {
        return ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ORDER_ANCHOR_CONFLICT");
    }

    static void requireDistinctStatus(String fromStatus, String toStatus) {
        if (Objects.equals(fromStatus, toStatus))
            throw ApplicationException.withReason(
                    StandardErrorCode.INVALID_STATE_TRANSITION, "WORK_ITEM_TRANSITION_NOT_ALLOWED");
    }

    static ApplicationException invalidRankAnchor() {
        return ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "WORK_ITEM_RANK_ANCHOR_INVALID");
    }

    static List<UUID> requestedAssignees(List<UUID> ids) {
        if (ids == null) return null;
        if (ids.stream().anyMatch(Objects::isNull)) throw validation("assigneeUserIds", "INVALID_VALUE", "处理人无效");
        if (new LinkedHashSet<>(ids).size() != ids.size()) throw validation("assigneeUserIds", "DUPLICATE", "处理人不可重复");
        if (ids.size() > WorkItem.MAX_ASSIGNEES) throw validation("assigneeUserIds", "TOO_MANY", "最多可设置 20 位处理人");
        return List.copyOf(ids);
    }

    static boolean canEdit(ProjectAccessSnapshot project, Content content) {
        return project.contentWritable()
                && content != null
                && project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }

    static String priority(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (!normalized.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw validation("priority", "INVALID_VALUE", "优先级无效");
        }
        return normalized;
    }

    static void requireAllowedPriorities(WorkItemQuery query, List<WorkItemLabelModels.PriorityLabel> priorities) {
        Set<String> allowed = priorities.stream()
                .map(WorkItemLabelModels.PriorityLabel::code)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(query.priorities())) throw validation("priority", "INVALID_VALUE", "优先级筛选值无效");
        if (query.group().priority() != null && !allowed.contains(query.group().priority()))
            throw validation("groupPriority", "INVALID_VALUE", "优先级分组值无效");
    }

    static void requireSelectablePriority(
            String requested, List<WorkItemLabelModels.PriorityLabel> priorities, String current) {
        if (requested == null) return;
        if (Objects.equals(requested, current)) return;
        boolean selectable = priorities.stream().anyMatch(label -> label.code().equals(requested) && label.active());
        if (!selectable) throw validation("priority", "INVALID_VALUE", "优先级不存在或已停用");
    }

    static WorkItemViewType view(String value) {
        if (value == null || value.isBlank()) return WorkItemViewType.TABLE;
        try {
            return WorkItemViewType.valueOf(value);
        } catch (RuntimeException exception) {
            throw validation("view", "INVALID_VALUE", "工作项视图类型无效");
        }
    }

    static void requireDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw validation("timelineEndDate", "INVALID_RANGE", "计划结束日不得早于计划开始日");
        }
    }

    static String normalizeResolution(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 500) throw validation("resolution", "INVALID_LENGTH", "迁移说明不能超过 500 字符");
        return normalized;
    }

    static String normalizeDeleteReason(String value) {
        if (value == null) throw validation("reason", "REQUIRED", "删除理由不能为空");
        String normalized = value.strip();
        if (normalized.isEmpty()) throw validation("reason", "REQUIRED", "删除理由不能为空");
        if (normalized.length() > 500) throw validation("reason", "INVALID_LENGTH", "删除理由不能超过 500 字符");
        return normalized;
    }

    static ApplicationException invalidLifecycle(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }

    static void requireActiveContent(Content content) {
        if (!content.active()) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, "CONTENT_ARCHIVED");
        }
    }

    static void requireVersion(WorkItem item, long expectedVersion) {
        if (item.rowVersion() != expectedVersion) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }

    static void requireWritableAccess(ProjectAccessSnapshot.ActorProjectAccess actorAccess) {
        if (actorAccess == ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }

    static void requireWritableAccess(ProjectFactWriteSnapshot.ActorProjectAccess actorAccess) {
        if (actorAccess == ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }

    static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
