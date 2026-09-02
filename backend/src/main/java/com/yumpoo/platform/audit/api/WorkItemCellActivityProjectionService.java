package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.WorkItemCellActivityRepository;
import com.yumpoo.platform.audit.application.WorkItemCellActivityStoredEvent;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxConsumerException;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class WorkItemCellActivityProjectionService implements OutboxEventConsumer {
    private static final String CREATED = "workitem.work_item_created";
    private static final String FIELDS_CHANGED = "workitem.work_item_fields_changed";
    private static final String ASSIGNED = "workitem.work_item_assigned";
    private static final String UNASSIGNED = "workitem.work_item_unassigned";
    private static final String STATUS_CHANGED = "workitem.work_item_status_changed";
    private static final Set<String> EVENTS = Set.of(CREATED, FIELDS_CHANGED, ASSIGNED,
            UNASSIGNED, STATUS_CHANGED);

    private final WorkItemCellActivityRepository repository;
    private final ActivityProjectionContextPort context;
    private final ObjectMapper objectMapper;

    public WorkItemCellActivityProjectionService(WorkItemCellActivityRepository repository,
            ActivityProjectionContextPort context, ObjectMapper objectMapper) {
        this.repository = repository;
        this.context = context;
        this.objectMapper = objectMapper;
    }

    @Override
    public String consumerName() {
        return "work-item-cell-activity-v1";
    }

    @Override
    public Set<EventSubscription> subscriptions() {
        LinkedHashSet<EventSubscription> result = new LinkedHashSet<>();
        EVENTS.forEach(type -> result.add(new EventSubscription(type, 1)));
        return Set.copyOf(result);
    }

    @Override
    public void consume(DomainEventEnvelope event) {
        if (event.occurredAt().isBefore(repository.acceptedFrom())) return;
        try {
            switch (event.eventType()) {
                case CREATED -> created(event);
                case FIELDS_CHANGED -> fieldsChanged(event);
                case ASSIGNED, UNASSIGNED -> assignee(event);
                case STATUS_CHANGED -> status(event);
                default -> throw invalid();
            }
        } catch (OutboxConsumerException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private void created(DomainEventEnvelope event) {
        append(event, "WORK_ITEM_NAME", "CREATED", null,
                value("TEXT", null, text(event.payload(), "title"), null));
    }

    private void fieldsChanged(DomainEventEnvelope event) {
        ArrayNode fields = array(event.payload(), "changedFields");
        for (JsonNode field : fields) {
            if (!field.isTextual()) throw invalid();
            switch (field.textValue()) {
                case "title" -> append(event, "WORK_ITEM_NAME", "CHANGED",
                        value("TEXT", null, requiredPrevious(event.payload(), "previousTitle"), null),
                        value("TEXT", null, text(event.payload(), "title"), null));
                case "priority" -> changedLabel(event, "PRIORITY", "previousPriority", "priority");
                case "dueDate" -> changedScalar(event, "DUE_DATE", "DATE",
                        "previousDueDate", "dueDate");
                default -> { }
            }
        }
    }

    private void assignee(DomainEventEnvelope event) {
        UUID beforeId = nullableUuid(event.payload(), "previousAssigneeUserId");
        UUID afterId = nullableUuid(event.payload(), "assigneeUserId");
        append(event, "ASSIGNEE", change(beforeId, afterId), member(event, beforeId),
                member(event, afterId));
    }

    private void status(DomainEventEnvelope event) {
        String before = text(event.payload(), "fromStatus");
        String after = text(event.payload(), "toStatus");
        append(event, "STATUS", "CHANGED", label(event, "STATUS", before),
                label(event, "STATUS", after));
    }

    private void changedLabel(DomainEventEnvelope event, String column, String beforeField,
            String afterField) {
        String before = nullableText(event.payload(), beforeField);
        String after = nullableText(event.payload(), afterField);
        append(event, column, change(before, after), label(event, column, before),
                label(event, column, after));
    }

    private void changedScalar(DomainEventEnvelope event, String column, String type,
            String beforeField, String afterField) {
        String before = nullableText(event.payload(), beforeField);
        String after = nullableText(event.payload(), afterField);
        append(event, column, change(before, after), value(type, null, before, null),
                value(type, null, after, null));
    }

    private ObjectNode label(DomainEventEnvelope event, String kind, String code) {
        if (code == null) return null;
        UUID projectId = uuid(event.payload(), "projectId");
        ActivityProjectionContextPort.LabelReference label = "STATUS".equals(kind)
                ? context.status(event.companyId(), projectId, code).orElse(null)
                : context.priority(event.companyId(), projectId, code).orElse(null);
        return value("LABEL", code, label == null ? code : label.displayName(),
                label == null ? null : label.colorToken());
    }

    private ObjectNode member(DomainEventEnvelope event, UUID userId) {
        return userId == null ? null : value("MEMBER", userId.toString(),
                context.userDisplayName(event.companyId(), userId).orElse("已停用成员"), null);
    }

    private ObjectNode value(String type, String referenceId, String displayName, String color) {
        if (displayName == null) return null;
        ObjectNode value = objectMapper.createObjectNode();
        value.put("type", type);
        if (referenceId != null) value.put("referenceId", referenceId);
        value.put("displayName", displayName);
        if (color != null) value.put("colorToken", color);
        return value;
    }

    private void append(DomainEventEnvelope event, String column, String change,
            JsonNode before, JsonNode after) {
        JsonNode payload = event.payload();
        UUID projectId = uuid(payload, "projectId");
        UUID contentId = uuid(payload, "contentId");
        String contentName = context.content(event.companyId(), projectId, contentId)
                .map(ActivityProjectionContextPort.ContentReference::displayName)
                .orElse("未知类别");
        UUID actorUserId = event.actor().userId();
        String actorDisplay = actorUserId == null ? "系统"
                : context.userDisplayName(event.companyId(), actorUserId).orElse("已停用成员");
        repository.append(new WorkItemCellActivityStoredEvent(UUID.randomUUID(), event.eventId(),
                event.companyId(), projectId, uuid(payload, "workItemId"), contentId, contentName,
                event.eventType(), column, change, before, after, event.actor().type().name(),
                actorUserId, event.actor().systemCode(), actorDisplay, event.occurredAt(),
                event.requestId(), event.correlationId()));
    }

    private static String change(Object before, Object after) {
        if (before == null) return "ADDED";
        if (after == null) return "REMOVED";
        return "CHANGED";
    }

    private static UUID uuid(JsonNode payload, String field) {
        String value = text(payload, field);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static UUID nullableUuid(JsonNode payload, String field) {
        String value = nullableText(payload, field);
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static String requiredPrevious(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String nullableText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null) throw invalid();
        if (value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static ArrayNode array(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (!(value instanceof ArrayNode array)) throw invalid();
        return array;
    }

    private static OutboxConsumerException invalid() {
        return new OutboxConsumerException("WORK_ITEM_CELL_ACTIVITY_INVALID_V1_PAYLOAD", false);
    }
}
