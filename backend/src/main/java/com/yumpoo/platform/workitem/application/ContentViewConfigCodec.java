package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ContentViewConfig.*;

@Component
public final class ContentViewConfigCodec {
    static final int MAX_BYTES = 16 * 1024;
    private static final List<TableColumn> COLUMNS = List.of(TableColumn.values());
    private static final List<TableColumn> DEFAULT_HIDDEN = List.of(
            TableColumn.REPORTER, TableColumn.DESCRIPTION, TableColumn.NOTES, TableColumn.TIMELINE);
    private final ObjectMapper objectMapper;

    public ContentViewConfigCodec(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public ContentViewConfig normalize(JsonNode input,
            List<ProjectTemplateSnapshot.WorkflowStatus> statuses) {
        JsonNode root = input == null ? objectMapper.createObjectNode() : input;
        requireObject(root, "viewConfig");
        ensureSize(root);
        rejectUnknown(root, Set.of("table", "kanban"), "viewConfig");
        ContentViewConfig result = new ContentViewConfig(
                table(root.get("table"), statuses), kanban(root.get("kanban"), statuses));
        if (write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid("viewConfig", "TOO_LARGE", "视图配置不得超过 16 KiB");
        return result;
    }

    public ContentViewConfig read(String json, List<ProjectTemplateSnapshot.WorkflowStatus> statuses) {
        try { return normalize(objectMapper.readTree(json), statuses); }
        catch (JacksonException exception) {
            throw new IllegalStateException("stored content view config is invalid", exception);
        }
    }

    public String write(ContentViewConfig config) {
        try { return objectMapper.writeValueAsString(config); }
        catch (JacksonException exception) {
            throw new IllegalStateException("content view config serialization failed", exception);
        }
    }

    private Table table(JsonNode node, List<ProjectTemplateSnapshot.WorkflowStatus> statuses) {
        if (node == null || node.isNull()) node = objectMapper.createObjectNode();
        requireObject(node, "viewConfig.table");
        rejectUnknown(node, Set.of("columnOrder", "hiddenColumns", "sort", "filters"),
                "viewConfig.table");
        List<TableColumn> order = enumList(node.get("columnOrder"), TableColumn.class,
                "viewConfig.table.columnOrder", COLUMNS);
        List<TableColumn> normalizedOrder = new ArrayList<>(order);
        COLUMNS.stream().filter(value -> !normalizedOrder.contains(value)).forEach(normalizedOrder::add);
        List<TableColumn> hidden = enumList(node.get("hiddenColumns"), TableColumn.class,
                "viewConfig.table.hiddenColumns", DEFAULT_HIDDEN);
        if (hidden.contains(TableColumn.TITLE))
            throw invalid("viewConfig.table.hiddenColumns", "TITLE_REQUIRED", "标题列不可隐藏");
        List<Sort> sort = sorts(node.get("sort"));
        return new Table(normalizedOrder, hidden, sort, filters(node.get("filters"), statuses));
    }

    private List<Sort> sorts(JsonNode node) {
        if (node == null || node.isNull()) return List.of(new Sort(SortField.UPDATED_AT, SortDirection.DESC));
        requireArray(node, "viewConfig.table.sort");
        if (node.size() > 3) throw invalid("viewConfig.table.sort", "TOO_MANY", "最多配置三个排序字段");
        List<Sort> result = new ArrayList<>();
        Set<SortField> fields = new HashSet<>();
        for (JsonNode item : node) {
            requireObject(item, "viewConfig.table.sort");
            rejectUnknown(item, Set.of("field", "direction"), "viewConfig.table.sort");
            SortField field = enumValue(item.get("field"), SortField.class, "viewConfig.table.sort.field");
            SortDirection direction = enumValue(item.get("direction"), SortDirection.class,
                    "viewConfig.table.sort.direction");
            if (!fields.add(field)) throw invalid("viewConfig.table.sort", "DUPLICATE", "排序字段不得重复");
            result.add(new Sort(field, direction));
        }
        return result;
    }

    private Filters filters(JsonNode node, List<ProjectTemplateSnapshot.WorkflowStatus> statuses) {
        if (node == null || node.isNull()) node = objectMapper.createObjectNode();
        requireObject(node, "viewConfig.table.filters");
        rejectUnknown(node, Set.of("query", "statusCodes", "priorities", "assigneeUserIds",
                "dueFrom", "dueTo", "updatedAfter"), "viewConfig.table.filters");
        String query = optionalText(node.get("query"), "viewConfig.table.filters.query");
        List<String> statusCodes = textList(node.get("statusCodes"),
                "viewConfig.table.filters.statusCodes", List.of());
        Set<String> validStatuses = statuses.stream().map(ProjectTemplateSnapshot.WorkflowStatus::statusCode)
                .collect(java.util.stream.Collectors.toSet());
        if (!validStatuses.containsAll(statusCodes))
            throw invalid("viewConfig.table.filters.statusCodes", "UNKNOWN_STATUS", "筛选状态必须属于项目模板");
        List<Priority> priorities = enumList(node.get("priorities"), Priority.class,
                "viewConfig.table.filters.priorities", List.of());
        List<UUID> assignees = textList(node.get("assigneeUserIds"),
                "viewConfig.table.filters.assigneeUserIds", List.of()).stream().map(value -> {
            try { return UUID.fromString(value); }
            catch (IllegalArgumentException exception) {
                throw invalid("viewConfig.table.filters.assigneeUserIds", "INVALID_UUID", "负责人必须为 UUID");
            }
        }).toList();
        LocalDate dueFrom = date(node.get("dueFrom"), "viewConfig.table.filters.dueFrom");
        LocalDate dueTo = date(node.get("dueTo"), "viewConfig.table.filters.dueTo");
        if (dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo))
            throw invalid("viewConfig.table.filters.dueTo", "INVALID_RANGE", "截止日期范围无效");
        Instant updatedAfter = instant(node.get("updatedAfter"), "viewConfig.table.filters.updatedAfter");
        return new Filters(query, statusCodes, priorities, assignees, dueFrom, dueTo, updatedAfter);
    }

    private Kanban kanban(JsonNode node, List<ProjectTemplateSnapshot.WorkflowStatus> statuses) {
        if (node == null || node.isNull()) node = objectMapper.createObjectNode();
        requireObject(node, "viewConfig.kanban");
        rejectUnknown(node, Set.of("statusGroups"), "viewConfig.kanban");
        JsonNode groupsNode = node.get("statusGroups");
        if (groupsNode == null || groupsNode.isNull() || (groupsNode.isArray() && groupsNode.isEmpty())) {
            return new Kanban(statuses.stream().map(status -> new StatusGroup(
                    status.displayName(), List.of(status.statusCode()))).toList());
        }
        requireArray(groupsNode, "viewConfig.kanban.statusGroups");
        List<StatusGroup> groups = new ArrayList<>();
        List<String> covered = new ArrayList<>();
        for (JsonNode item : groupsNode) {
            requireObject(item, "viewConfig.kanban.statusGroups");
            rejectUnknown(item, Set.of("name", "statusCodes"), "viewConfig.kanban.statusGroups");
            String name = requiredText(item.get("name"), "viewConfig.kanban.statusGroups.name");
            if (name.length() > 80) throw invalid("viewConfig.kanban.statusGroups.name", "INVALID_LENGTH", "分组名称过长");
            List<String> codes = textList(item.get("statusCodes"),
                    "viewConfig.kanban.statusGroups.statusCodes", List.of());
            if (codes.isEmpty()) throw invalid("viewConfig.kanban.statusGroups.statusCodes", "REQUIRED", "分组至少包含一个状态");
            covered.addAll(codes);
            groups.add(new StatusGroup(name, codes));
        }
        List<String> expected = statuses.stream().map(ProjectTemplateSnapshot.WorkflowStatus::statusCode).toList();
        if (covered.size() != new HashSet<>(covered).size() || !new HashSet<>(covered).equals(new HashSet<>(expected)))
            throw invalid("viewConfig.kanban.statusGroups", "INVALID_PARTITION", "看板分组必须且只能覆盖全部模板状态一次");
        return new Kanban(groups);
    }

    private <E extends Enum<E>> List<E> enumList(JsonNode node, Class<E> type, String field,
                                                   List<E> defaults) {
        if (node == null || node.isNull()) return defaults;
        requireArray(node, field);
        LinkedHashSet<E> result = new LinkedHashSet<>();
        for (JsonNode item : node) {
            E value = enumValue(item, type, field);
            if (!result.add(value)) throw invalid(field, "DUPLICATE", "列表值不得重复");
        }
        return List.copyOf(result);
    }

    private <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field) {
        String value = requiredText(node, field);
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException exception) { throw invalid(field, "INVALID_VALUE", "枚举值无效"); }
    }

    private List<String> textList(JsonNode node, String field, List<String> defaults) {
        if (node == null || node.isNull()) return defaults;
        requireArray(node, field);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = requiredText(item, field);
            if (!result.add(value)) throw invalid(field, "DUPLICATE", "列表值不得重复");
        }
        return List.copyOf(result);
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        return requiredText(node, field);
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.asText().strip().isEmpty())
            throw invalid(field, "REQUIRED", "字段必须为非空字符串");
        return node.asText().strip();
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException exception) { throw invalid(field, "INVALID_DATE", "日期格式必须为 YYYY-MM-DD"); }
    }

    private static Instant instant(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) { throw invalid(field, "INVALID_INSTANT", "时间格式必须为 UTC ISO-8601"); }
    }

    private void ensureSize(JsonNode node) {
        try {
            if (objectMapper.writeValueAsBytes(node).length > MAX_BYTES)
                throw invalid("viewConfig", "TOO_LARGE", "视图配置不得超过 16 KiB");
        } catch (JacksonException exception) {
            throw invalid("viewConfig", "INVALID_JSON", "视图配置无法序列化");
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String field) {
        List<String> unknown = node.properties().stream().map(Map.Entry::getKey)
                .filter(name -> !allowed.contains(name)).sorted().toList();
        if (!unknown.isEmpty()) throw invalid(field, "UNKNOWN_FIELD", "存在未知字段：" + String.join(", ", unknown));
    }

    private static void requireObject(JsonNode node, String field) {
        if (!node.isObject()) throw invalid(field, "INVALID_TYPE", "字段必须为对象");
    }

    private static void requireArray(JsonNode node, String field) {
        if (!node.isArray()) throw invalid(field, "INVALID_TYPE", "字段必须为数组");
    }

    private static ApplicationException invalid(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
