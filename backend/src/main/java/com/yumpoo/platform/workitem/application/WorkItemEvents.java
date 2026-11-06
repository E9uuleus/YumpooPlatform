package com.yumpoo.platform.workitem.application;

import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemRelationRepository.ParentChildRelation;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class WorkItemEvents {
    private static final String CREATED = "workitem.work_item_created";
    static final String FIELDS_CHANGED = "workitem.work_item_fields_changed";
    private static final String ASSIGNED = "workitem.work_item_assigned";
    private static final String UNASSIGNED = "workitem.work_item_unassigned";
    private static final String ASSIGNEES_CHANGED = "workitem.work_item_assignees_changed";
    private static final String STATUS_CHANGED = "workitem.work_item_status_changed";
    private static final String RANK_CHANGED = "workitem.work_item_rank_changed";
    private static final String DELETED = "workitem.work_item_deleted";
    private static final String RESTORED = "workitem.work_item_restored";
    private static final String RELATION_CREATED = "workitem.work_item_relation_created";
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;

    public WorkItemEvents(TransactionalEventPort events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    void appendCreated(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = commonEventPayload(item);
        payload.put("reporterUserId", item.reporterUserId());
        payload.put("assigneeUserIds", item.assigneeUserIds());
        append(CREATED, item, actor, payload);
    }

    void appendRelationCreated(ParentChildRelation relation, CurrentActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("relationId", relation.id());
        payload.put("relationType", "PARENT_CHILD");
        payload.put("leftWorkItemId", relation.parentWorkItemId());
        payload.put("rightWorkItemId", relation.childWorkItemId());
        payload.put("leftProjectId", relation.projectId());
        payload.put("rightProjectId", relation.projectId());
        events.append(new EventDraft(
                RELATION_CREATED,
                1,
                "WorkItemRelation",
                relation.id(),
                0,
                relation.companyId(),
                EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    void appendFieldsChanged(WorkItem before, WorkItem item, CurrentActor actor, List<String> changedFields) {
        Map<String, Object> payload = commonEventPayload(item);
        payload.put("previousTitle", before.title());
        payload.put("previousPriority", before.priority());
        payload.put("previousDueDate", before.dueDate());
        payload.put("previousDueTime", dueTimeText(before));
        payload.put("changedFields", changedFields);
        append(FIELDS_CHANGED, item, actor, payload);
    }

    void appendAssignmentChange(WorkItem before, WorkItem after, CurrentActor actor) {
        if (Objects.equals(before.assigneeUserId(), after.assigneeUserId())) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", after.id());
        payload.put("projectId", after.projectId());
        payload.put("contentId", after.contentId());
        payload.put("itemNo", after.itemNo());
        payload.put("assigneeUserIds", after.assigneeUserIds());
        payload.put("previousAssigneeUserId", before.assigneeUserId());
        payload.put("assigneeUserId", after.assigneeUserId());
        payload.put("rowVersion", after.rowVersion());
        append(after.assigneeUserId() == null ? UNASSIGNED : ASSIGNED, after, actor, payload);
    }

    void appendAssigneesChange(WorkItem before, WorkItem after, CurrentActor actor) {
        if (new LinkedHashSet<>(before.assigneeUserIds()).equals(new LinkedHashSet<>(after.assigneeUserIds()))) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", after.id());
        payload.put("projectId", after.projectId());
        payload.put("contentId", after.contentId());
        payload.put("itemNo", after.itemNo());
        payload.put("previousAssigneeUserIds", before.assigneeUserIds());
        payload.put("assigneeUserIds", after.assigneeUserIds());
        payload.put(
                "addedUserIds",
                after.assigneeUserIds().stream()
                        .filter(id -> !before.assigneeUserIds().contains(id))
                        .toList());
        payload.put(
                "removedUserIds",
                before.assigneeUserIds().stream()
                        .filter(id -> !after.assigneeUserIds().contains(id))
                        .toList());
        payload.put("rowVersion", after.rowVersion());
        append(ASSIGNEES_CHANGED, after, actor, payload);
    }

    void appendStatusChanged(WorkItem before, WorkItem after, CurrentActor actor, String resolution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", after.id());
        payload.put("projectId", after.projectId());
        payload.put("contentId", after.contentId());
        payload.put("itemNo", after.itemNo());
        payload.put("title", after.title());
        payload.put("fromStatus", before.statusCode());
        payload.put("toStatus", after.statusCode());
        payload.put("fromStatusCategory", before.statusCategory().name());
        payload.put("toStatusCategory", after.statusCategory().name());
        payload.put("resolution", resolution);
        payload.put("rowVersion", after.rowVersion());
        append(STATUS_CHANGED, after, actor, payload);
    }

    void appendRankChanged(WorkItem item, CurrentActor actor, WorkItemRankPlacement placement, UUID anchorWorkItemId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", item.id());
        payload.put("projectId", item.projectId());
        payload.put("contentId", item.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("statusCode", item.statusCode());
        payload.put("placement", placement.name());
        payload.put("anchorWorkItemId", anchorWorkItemId);
        payload.put("rowVersion", item.rowVersion());
        append(RANK_CHANGED, item, actor, payload);
    }

    void appendDeleted(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = lifecycleEventPayload(item);
        payload.put("deletedAt", item.deletedAt());
        payload.put("deletedByUserId", item.deletedByUserId());
        payload.put("deleteReason", item.deleteReason());
        append(DELETED, item, actor, payload);
    }

    void appendRestored(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = lifecycleEventPayload(item);
        payload.put("restoredAt", item.updatedAt());
        payload.put("restoredByUserId", actor.userId());
        append(RESTORED, item, actor, payload);
    }

    static Map<String, Object> lifecycleEventPayload(WorkItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", item.id());
        payload.put("projectId", item.projectId());
        payload.put("contentId", item.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        payload.put("statusCode", item.statusCode());
        payload.put("statusCategory", item.statusCategory().name());
        payload.put("priority", priorityName(item));
        payload.put("rowVersion", item.rowVersion());
        return payload;
    }

    static Map<String, Object> commonEventPayload(WorkItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", item.id());
        payload.put("projectId", item.projectId());
        payload.put("contentId", item.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        payload.put("statusCode", item.statusCode());
        payload.put("statusCategory", item.statusCategory().name());
        payload.put("priority", priorityName(item));
        payload.put("assigneeUserId", item.assigneeUserId());
        payload.put("timelineStartDate", item.timelineStartDate());
        payload.put("timelineEndDate", item.timelineEndDate());
        payload.put("dueDate", item.dueDate());
        payload.put("dueTime", dueTimeText(item));
        payload.put("rowVersion", item.rowVersion());
        return payload;
    }

    void append(String eventType, WorkItem item, CurrentActor actor, Map<String, Object> payload) {
        int schemaVersion = Set.of(CREATED, FIELDS_CHANGED, STATUS_CHANGED, DELETED, RESTORED)
                        .contains(eventType)
                ? 2
                : 1;
        events.append(new EventDraft(
                eventType,
                schemaVersion,
                "WorkItem",
                item.id(),
                item.rowVersion(),
                item.companyId(),
                EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    static List<String> changed(WorkItem before, WorkItem after) {
        List<String> fields = new ArrayList<>();
        if (!before.title().equals(after.title())) fields.add("title");
        if (!Objects.equals(before.priority(), after.priority())) fields.add("priority");
        if (!before.assigneeUserIds().equals(after.assigneeUserIds())) fields.add("assigneeUserId");
        if (!Objects.equals(before.description(), after.description())) fields.add("description");
        if (!Objects.equals(before.notes(), after.notes())) fields.add("notes");
        if (!Objects.equals(before.timelineStartDate(), after.timelineStartDate())) fields.add("timelineStartDate");
        if (!Objects.equals(before.timelineEndDate(), after.timelineEndDate())) fields.add("timelineEndDate");
        if (!Objects.equals(before.dueDate(), after.dueDate()) || !Objects.equals(before.dueTime(), after.dueTime()))
            fields.add("dueDate");
        return List.copyOf(fields);
    }

    static String dueTimeText(WorkItem item) {
        return item.dueTime() == null ? null : item.dueTime().toString();
    }

    static String priorityName(WorkItem item) {
        return item.priority();
    }
}
