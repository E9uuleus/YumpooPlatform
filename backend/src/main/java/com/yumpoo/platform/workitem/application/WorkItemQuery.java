package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemSortDirection.*;

public record WorkItemQuery(
        String query,
        Set<String> statuses,
        Set<String> priorities,
        Set<UUID> assigneeUserIds,
        Set<UUID> contentIds,
        LocalDate dueFrom,
        LocalDate dueTo,
        Instant updatedAfter,
        List<Sort> sorts
) {
    public WorkItemQuery {
        statuses = Set.copyOf(statuses);
        priorities = Set.copyOf(priorities);
        assigneeUserIds = Set.copyOf(assigneeUserIds);
        contentIds = Set.copyOf(contentIds);
        sorts = List.copyOf(sorts);
    }

    public record Sort(WorkItemSortField field, WorkItemSortDirection direction) {}

    public record Request(String query, Collection<String> statuses,
                          Collection<String> priorities, Collection<UUID> assigneeUserIds,
                          Collection<UUID> contentIds,
                          LocalDate dueFrom, LocalDate dueTo, Instant updatedAfter,
                          Collection<String> sorts) {}

    public static WorkItemQuery parse(Request request, Set<String> allowedStatuses) {
        if (request.dueFrom() != null && request.dueTo() != null
                && request.dueFrom().isAfter(request.dueTo()))
            throw invalid("dueTo", "INVALID_RANGE", "截止日期范围无效");
        return new WorkItemQuery(normalizeQuery(request.query()),
                statuses(request.statuses(), allowedStatuses), priorities(request.priorities()),
                request.assigneeUserIds() == null ? Set.of()
                        : new LinkedHashSet<>(request.assigneeUserIds()),
                request.contentIds() == null ? Set.of() : new LinkedHashSet<>(request.contentIds()),
                request.dueFrom(), request.dueTo(), request.updatedAfter(), sorts(request.sorts()));
    }

    private static String normalizeQuery(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private static Set<String> statuses(Collection<String> requested, Set<String> allowed) {
        if (requested == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            String status = value == null ? null : value.strip();
            if (status == null || !allowed.contains(status))
                throw invalid("status", "UNKNOWN_STATUS", "状态必须属于 Project 固定模板");
            result.add(status);
        }
        return result;
    }

    private static Set<String> priorities(Collection<String> requested) {
        if (requested == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            String normalized = value == null ? null : value.strip();
            if (normalized == null || !normalized.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
                throw invalid("priority", "INVALID_VALUE", "优先级筛选值无效");
            }
            result.add(normalized);
        }
        return result;
    }

    private static List<Sort> sorts(Collection<String> requested) {
        if (requested == null || requested.isEmpty())
            return List.of();
        if (requested.size() > 3)
            throw invalid("sort", "TOO_MANY", "最多配置三个排序字段");
        List<Sort> result = new ArrayList<>();
        Set<WorkItemSortField> fields = new HashSet<>();
        for (String value : requested) {
            String[] parts = value == null ? new String[0] : value.split(",", -1);
            WorkItemSortField field;
            WorkItemSortDirection direction;
            try {
                if (parts.length != 2) throw new IllegalArgumentException();
                field = WorkItemSortField.valueOf(parts[0]);
                direction = WorkItemSortDirection.valueOf(parts[1]);
            } catch (IllegalArgumentException exception) {
                throw invalid("sort", "INVALID_VALUE", "排序必须使用 FIELD,DIRECTION 白名单格式");
            }
            if (!fields.add(field))
                throw invalid("sort", "DUPLICATE", "排序字段不得重复");
            result.add(new Sort(field, direction));
        }
        return result;
    }

    private static ApplicationException invalid(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
