package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ContentViewConfig(Table table, Kanban kanban) {
    public ContentViewConfig {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(kanban, "kanban must not be null");
    }

    public record Table(List<TableColumn> columnOrder, List<TableColumn> hiddenColumns,
                        List<Sort> sort, Filters filters) {
        public Table {
            columnOrder = List.copyOf(columnOrder);
            hiddenColumns = List.copyOf(hiddenColumns);
            sort = List.copyOf(sort);
            Objects.requireNonNull(filters, "filters must not be null");
        }
    }

    public record Sort(SortField field, SortDirection direction) {}

    public record Filters(String query, List<String> statusCodes, List<Priority> priorities,
                          List<UUID> assigneeUserIds, LocalDate dueFrom, LocalDate dueTo,
                          Instant updatedAfter) {
        public Filters {
            statusCodes = List.copyOf(statusCodes);
            priorities = List.copyOf(priorities);
            assigneeUserIds = List.copyOf(assigneeUserIds);
        }
    }

    public record Kanban(List<StatusGroup> statusGroups) {
        public Kanban { statusGroups = List.copyOf(statusGroups); }
    }

    public record StatusGroup(String name, List<String> statusCodes) {
        public StatusGroup { statusCodes = List.copyOf(statusCodes); }
    }

    public enum TableColumn {
        ITEM_NO, TITLE, STATUS, PRIORITY, ASSIGNEE, REPORTER, DESCRIPTION, NOTES,
        TIMELINE, DUE_DATE, UPDATED_AT
    }

    public enum SortField {
        ITEM_NO, TITLE, STATUS, PRIORITY, ASSIGNEE, REPORTER, TIMELINE_START_DATE,
        TIMELINE_END_DATE, DUE_DATE, UPDATED_AT
    }

    public enum SortDirection { ASC, DESC }
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }
}
