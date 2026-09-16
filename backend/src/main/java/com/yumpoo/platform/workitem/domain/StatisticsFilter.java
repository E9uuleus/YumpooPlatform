package com.yumpoo.platform.workitem.domain;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record StatisticsFilter(List<UUID> projectIds, List<String> assignees, List<String> statuses,
        List<String> priorities, List<UUID> contentIds, List<String> categories, LocalDate dueFrom,
        LocalDate dueTo, boolean includeArchived, String query, boolean hasTime) {
    public StatisticsFilter {
        projectIds = values(projectIds); assignees = values(assignees); statuses = values(statuses);
        priorities = values(priorities); contentIds = values(contentIds); categories = values(categories);
        query = query == null ? "" : query.strip();
        if (query.length() > 200 || (dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo))) invalid();
        if (categories.stream().anyMatch(c -> !Set.of("TODO", "IN_PROGRESS", "DONE", "CANCELED").contains(c))) invalid();
        for (String value : assignees) {
            if (!value.equals("UNASSIGNED")) uuid(value);
        }
        for (String value : java.util.stream.Stream.concat(statuses.stream(), priorities.stream()).toList()) {
            String[] parts = value.split(":", -1);
            if (parts.length != 2 || !parts[1].matches("[A-Z][A-Z0-9_]{1,31}|UNASSIGNED")) invalid();
            uuid(parts[0]);
        }
    }
    public StatisticsFilter within(List<UUID> scope) {
        return new StatisticsFilter(scope, assignees, statuses, priorities, contentIds, categories,
                dueFrom, dueTo, includeArchived, query, hasTime);
    }
    public StatisticsFilter options() {
        return new StatisticsFilter(projectIds, List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, includeArchived, "", false);
    }
    private static <T> List<T> values(List<T> values) {
        if (values == null) return List.of();
        if (values.size() > 500 || values.stream().anyMatch(java.util.Objects::isNull)) invalid();
        return values.stream().distinct().toList();
    }
    private static void uuid(String value) {
        try { if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) invalid(); }
        catch (IllegalArgumentException ignored) { invalid(); }
    }
    private static void invalid() {
        throw ApplicationException.validation(new FieldViolation("filters", "INVALID_VALUE", "筛选条件无效"));
    }
}
