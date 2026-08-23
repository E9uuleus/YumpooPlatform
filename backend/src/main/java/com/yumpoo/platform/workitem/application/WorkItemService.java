package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ContentStatus;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemPriority;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemCommands.Create;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Transition;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Update;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;

@Service
public class WorkItemService {
    private static final String CREATED = "workitem.work_item_created";
    private static final String FIELDS_CHANGED = "workitem.work_item_fields_changed";
    private static final String ASSIGNED = "workitem.work_item_assigned";
    private static final String UNASSIGNED = "workitem.work_item_unassigned";
    private static final String STATUS_CHANGED = "workitem.work_item_status_changed";

    private final WorkItemRepository workItems;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectActiveMembershipQuery activeMemberships;
    private final ProjectTemplateVersionQuery templates;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkItemService(WorkItemRepository workItems, ContentRepository contents,
            ProjectAccessSnapshotQuery access, ProjectFactWriteGuard writeGuard,
            ProjectActiveMembershipQuery activeMemberships,
            ProjectTemplateVersionQuery templates, MinimalUserSnapshotQuery users,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events,
            ObjectMapper objectMapper, Clock clock) {
        this.workItems = workItems;
        this.contents = contents;
        this.access = access;
        this.writeGuard = writeGuard;
        this.activeMemberships = activeMemberships;
        this.templates = templates;
        this.users = users;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkItemPage list(CurrentActor actor, UUID contentId,
            WorkItemQuery.Request request, OffsetPageRequest page) {
        VisibleContent visible = visibleContent(actor, contentId);
        ProjectTemplateSnapshot template = template(visible.project().templateKey(),
                visible.project().templateVersion());
        Set<String> allowedStatuses = template.statuses().stream()
                .map(ProjectTemplateSnapshot.WorkflowStatus::statusCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        WorkItemSortRanks ranks = sortRanks(visible.project().companyId(),
                visible.project().projectId(), contentId, query, template);
        List<WorkItem> rows = workItems.findPage(visible.project().companyId(),
                visible.project().projectId(), contentId, query, ranks, page);
        long total = workItems.countPage(visible.project().companyId(),
                visible.project().projectId(), contentId, query);
        Map<UUID, MinimalUserSnapshot> people = people(visible.project().companyId(), rows);
        OffsetPageResponse<WorkItemSummary> response = OffsetPageResponse.of(rows.stream()
                .map(item -> summary(item, people)).toList(), page, total);
        return new WorkItemPage(response.items(), response.page(), response.size(),
                response.totalElements(), response.totalPages());
    }

    @Transactional(readOnly = true)
    public WorkItemDetail find(CurrentActor actor, UUID workItemId) {
        requireActor(actor);
        WorkItemLocator locator = workItems.findLocator(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems.find(project.companyId(), project.projectId(),
                        locator.contentId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return detail(item, people(project.companyId(), List.of(item)), canEdit(project, content),
                template(project.templateKey(), project.templateVersion()));
    }

    public IdempotencyExecutionResult create(Create command) {
        VisibleContent visible = visibleContent(command.actor(), command.contentId());
        requireWritableAccess(visible.project().actorAccess());
        WorkItemPriority priority = priority(command.priority());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "createWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                    command.actor(), visible.project().projectId());
            requireWritableAccess(project.actorAccess());
            Content content = contents.lockForShare(project.companyId(), project.projectId(),
                            command.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireActiveContent(content);
            requireActiveAssignee(project, command.assigneeUserId());
            ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
            ProjectTemplateSnapshot.WorkflowStatus initial = template.statuses().stream()
                    .filter(ProjectTemplateSnapshot.WorkflowStatus::initial).findFirst()
                    .orElseThrow(() -> ApplicationException.withReason(
                            StandardErrorCode.INVALID_STATE_TRANSITION,
                            "TEMPLATE_INITIAL_STATUS_MISSING"));
            long sequence = workItems.nextSequence(project.companyId(), project.projectId());
            WorkItem item;
            try {
                item = WorkItem.create(UUID.randomUUID(), project.companyId(), project.projectId(),
                        content.id(), sequence, project.projectCode() + "-" + sequence,
                        content.workItemType(), command.title(), initial.statusCode(),
                        WorkItemStatusCategory.valueOf(initial.statusCategory()), priority,
                        command.assigneeUserId(), command.description(), command.notes(),
                        command.timelineStartDate(), command.timelineEndDate(), command.dueDate(),
                        command.actor().userId(), clock.instant());
            } catch (IllegalArgumentException exception) {
                throw validation("body", "INVALID_WORK_ITEM", exception.getMessage());
            }
            if (!workItems.insert(item)) throw new IllegalStateException("work item insert failed");
            appendCreated(item, command.actor());
            return stored(201, detail(item, people(project.companyId(), List.of(item)), true, template));
        });
    }

    @Transactional
    public WorkItemDetail update(Update command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        requireActiveAssignee(project, command.assigneeUserId());
        WorkItem candidate;
        try {
            candidate = before.updateFields(command.title(), priority(command.priority()),
                    command.assigneeUserId(), command.description(), command.notes(),
                    command.timelineStartDate(), command.timelineEndDate(), command.dueDate(),
                    command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", exception.getMessage());
        }
        List<String> changedFields = changed(before, candidate);
        if (changedFields.isEmpty()) {
            return detail(before, people(project.companyId(), List.of(before)), true, template);
        }
        WorkItem after = workItems.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        appendFieldsChanged(after, command.actor(), changedFields);
        appendAssignmentChange(before, after, command.actor());
        return detail(after, people(project.companyId(), List.of(after)), true, template);
    }

    public IdempotencyExecutionResult transition(Transition command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "transitionWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> transition(command, locator));
    }

    private StoredCommandResult transition(Transition command, WorkItemLocator locator) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
        ProjectTemplateSnapshot.WorkflowTransition edge = template.transitions().stream()
                .filter(value -> value.fromStatus().equals(before.statusCode())
                        && value.toStatus().equals(command.toStatus()))
                .findFirst().orElseThrow(() -> ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION,
                        "WORK_ITEM_TRANSITION_NOT_ALLOWED"));
        if (!"MEMBER".equals(edge.requiredPermission())) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "WORK_ITEM_TRANSITION_PERMISSION_UNSUPPORTED");
        }
        ProjectTemplateSnapshot.WorkflowStatus target = template.statuses().stream()
                .filter(value -> value.statusCode().equals(edge.toStatus())).findFirst()
                .orElseThrow(() -> ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION,
                        "WORK_ITEM_TARGET_STATUS_MISSING"));
        String resolution = normalizeResolution(command.resolution());
        if (edge.requiresResolution() && resolution == null) {
            throw validation("resolution", "REQUIRED", "该状态迁移必须填写说明");
        }
        WorkItem candidate;
        try {
            candidate = before.transitionStatus(target.statusCode(),
                    WorkItemStatusCategory.valueOf(target.statusCategory()),
                    command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "WORK_ITEM_TRANSITION_REJECTED");
        }
        WorkItem after = workItems.transition(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        appendStatusChanged(before, after, command.actor(), resolution);
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true, template));
    }

    private VisibleContent visibleContent(CurrentActor actor, UUID contentId) {
        requireActor(actor);
        ContentModels.ContentLocator locator = contents.findLocator(actor.companyId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), contentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return new VisibleContent(project, content);
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectTemplateSnapshot template(String key, int version) {
        return templates.findAny(key, version).orElseThrow(() -> ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "TEMPLATE_UNAVAILABLE"));
    }

    private Map<UUID, MinimalUserSnapshot> people(UUID companyId, List<WorkItem> rows) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (WorkItem item : rows) {
            userIds.add(item.reporterUserId());
            if (item.assigneeUserId() != null) userIds.add(item.assigneeUserId());
        }
        return users.findByUserIds(companyId, userIds);
    }

    private WorkItemSortRanks sortRanks(UUID companyId, UUID projectId, UUID contentId,
            WorkItemQuery query, ProjectTemplateSnapshot template) {
        Map<String, Integer> statusRanks = new LinkedHashMap<>();
        template.statuses().stream()
                .sorted(Comparator.comparingInt(ProjectTemplateSnapshot.WorkflowStatus::sortOrder))
                .forEach(status -> statusRanks.put(status.statusCode(), statusRanks.size()));
        boolean assigneeSort = query.sorts().stream()
                .anyMatch(sort -> sort.field() == ContentViewConfig.SortField.ASSIGNEE);
        boolean reporterSort = query.sorts().stream()
                .anyMatch(sort -> sort.field() == ContentViewConfig.SortField.REPORTER);
        if (!assigneeSort && !reporterSort)
            return new WorkItemSortRanks(statusRanks, Map.of(), Map.of());
        Set<UUID> participantIds = workItems.findParticipantUserIds(companyId, projectId, contentId);
        Map<UUID, MinimalUserSnapshot> snapshots = users.findByUserIds(companyId, participantIds);
        List<MinimalUserSnapshot> ordered = snapshots.values().stream()
                .sorted(Comparator.comparing(MinimalUserSnapshot::displayName,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MinimalUserSnapshot::displayName)
                        .thenComparing(snapshot -> snapshot.userId().toString()))
                .toList();
        Map<UUID, Integer> personRanks = new LinkedHashMap<>();
        ordered.forEach(snapshot -> personRanks.put(snapshot.userId(), personRanks.size()));
        return new WorkItemSortRanks(statusRanks,
                assigneeSort ? personRanks : Map.of(), reporterSort ? personRanks : Map.of());
    }

    private static WorkItemSummary summary(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people) {
        return new WorkItemSummary(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.type().name(), item.title(), item.statusCode(), item.statusCategory().name(),
                item.priority().name(), item.assigneeUserId(), assigneeDisplayName(item, people),
                item.reporterUserId(), displayName(people.get(item.reporterUserId())),
                item.description(), item.notes(), item.timelineStartDate(), item.timelineEndDate(),
                item.dueDate(), item.updatedAt());
    }

    private static WorkItemDetail detail(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people, boolean canEditFields,
            ProjectTemplateSnapshot template) {
        return new WorkItemDetail(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.type().name(), item.title(), item.statusCode(), item.statusCategory().name(),
                item.priority().name(), item.assigneeUserId(), assigneeDisplayName(item, people),
                item.reporterUserId(), displayName(people.get(item.reporterUserId())),
                item.description(), item.notes(), item.timelineStartDate(), item.timelineEndDate(),
                item.dueDate(), item.rowVersion(), StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(canEditFields,
                        availableTransitions(item, canEditFields, template)),
                item.createdAt(), item.updatedAt());
    }

    private static List<WorkItemTransitionOption> availableTransitions(WorkItem item,
            boolean canTransition, ProjectTemplateSnapshot template) {
        if (!canTransition) return List.of();
        Map<String, ProjectTemplateSnapshot.WorkflowStatus> statuses = new LinkedHashMap<>();
        template.statuses().forEach(status -> statuses.put(status.statusCode(), status));
        return template.transitions().stream()
                .filter(edge -> edge.fromStatus().equals(item.statusCode()))
                .filter(edge -> "MEMBER".equals(edge.requiredPermission()))
                .map(edge -> Map.entry(edge, statuses.get(edge.toStatus())))
                .filter(entry -> entry.getValue() != null)
                .sorted(Comparator.comparingInt(entry -> entry.getValue().sortOrder()))
                .map(entry -> new WorkItemTransitionOption(entry.getKey().toStatus(),
                        entry.getValue().displayName(), entry.getValue().statusCategory(),
                        entry.getKey().requiresResolution()))
                .toList();
    }

    private static String assigneeDisplayName(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people) {
        return item.assigneeUserId() == null ? null : displayName(people.get(item.assigneeUserId()));
    }

    private static String displayName(MinimalUserSnapshot person) {
        return person == null ? "未知成员" : person.displayName();
    }

    private void appendCreated(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = commonEventPayload(item);
        payload.put("reporterUserId", item.reporterUserId());
        append(CREATED, item, actor, payload);
    }

    private void appendFieldsChanged(WorkItem item, CurrentActor actor,
            List<String> changedFields) {
        Map<String, Object> payload = commonEventPayload(item);
        payload.put("changedFields", changedFields);
        append(FIELDS_CHANGED, item, actor, payload);
    }

    private void appendAssignmentChange(WorkItem before, WorkItem after, CurrentActor actor) {
        if (Objects.equals(before.assigneeUserId(), after.assigneeUserId())) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", after.id());
        payload.put("projectId", after.projectId());
        payload.put("contentId", after.contentId());
        payload.put("itemNo", after.itemNo());
        payload.put("previousAssigneeUserId", before.assigneeUserId());
        payload.put("assigneeUserId", after.assigneeUserId());
        payload.put("rowVersion", after.rowVersion());
        append(after.assigneeUserId() == null ? UNASSIGNED : ASSIGNED, after, actor, payload);
    }

    private void appendStatusChanged(WorkItem before, WorkItem after,
            CurrentActor actor, String resolution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", after.id());
        payload.put("projectId", after.projectId());
        payload.put("contentId", after.contentId());
        payload.put("itemNo", after.itemNo());
        payload.put("title", after.title());
        payload.put("workItemType", after.type().name());
        payload.put("fromStatus", before.statusCode());
        payload.put("toStatus", after.statusCode());
        payload.put("fromStatusCategory", before.statusCategory().name());
        payload.put("toStatusCategory", after.statusCategory().name());
        payload.put("resolution", resolution);
        payload.put("rowVersion", after.rowVersion());
        append(STATUS_CHANGED, after, actor, payload);
    }

    private static Map<String, Object> commonEventPayload(WorkItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", item.id());
        payload.put("projectId", item.projectId());
        payload.put("contentId", item.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        payload.put("workItemType", item.type().name());
        payload.put("statusCode", item.statusCode());
        payload.put("statusCategory", item.statusCategory().name());
        payload.put("priority", item.priority().name());
        payload.put("assigneeUserId", item.assigneeUserId());
        payload.put("timelineStartDate", item.timelineStartDate());
        payload.put("timelineEndDate", item.timelineEndDate());
        payload.put("dueDate", item.dueDate());
        payload.put("rowVersion", item.rowVersion());
        return payload;
    }

    private void append(String eventType, WorkItem item, CurrentActor actor,
            Map<String, Object> payload) {
        events.append(new EventDraft(eventType, 1, "WorkItem", item.id(), item.rowVersion(),
                item.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private StoredCommandResult stored(int status, WorkItemDetail view) {
        try {
            return new StoredCommandResult(status, objectMapper.writeValueAsString(view),
                    view.id(), view.etag());
        } catch (JacksonException exception) {
            throw new IllegalStateException("work item response serialization failed", exception);
        }
    }

    private void requireActiveAssignee(ProjectFactWriteSnapshot project, UUID assigneeUserId) {
        if (assigneeUserId != null && !activeMemberships.isActiveMember(
                project.companyId(), project.projectId(), assigneeUserId)) {
            throw validation("assigneeUserId", "NOT_ACTIVE_PROJECT_MEMBER",
                    "处理人必须是当前 Project 的 ACTIVE 成员");
        }
    }

    private static List<String> changed(WorkItem before, WorkItem after) {
        List<String> fields = new ArrayList<>();
        if (!before.title().equals(after.title())) fields.add("title");
        if (before.priority() != after.priority()) fields.add("priority");
        if (!Objects.equals(before.assigneeUserId(), after.assigneeUserId()))
            fields.add("assigneeUserId");
        if (!Objects.equals(before.description(), after.description())) fields.add("description");
        if (!Objects.equals(before.notes(), after.notes())) fields.add("notes");
        if (!Objects.equals(before.timelineStartDate(), after.timelineStartDate()))
            fields.add("timelineStartDate");
        if (!Objects.equals(before.timelineEndDate(), after.timelineEndDate()))
            fields.add("timelineEndDate");
        if (!Objects.equals(before.dueDate(), after.dueDate())) fields.add("dueDate");
        return List.copyOf(fields);
    }

    private static boolean canEdit(ProjectAccessSnapshot project, Content content) {
        return project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && content.status() == ContentStatus.ACTIVE
                && project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }

    private static WorkItemPriority priority(String value) {
        try {
            return WorkItemPriority.valueOf(value);
        } catch (RuntimeException exception) {
            throw validation("priority", "INVALID_VALUE", "优先级无效");
        }
    }

    private static void requireDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw validation("timelineEndDate", "INVALID_RANGE", "计划结束日不得早于计划开始日");
        }
    }

    private static String normalizeResolution(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 500)
            throw validation("resolution", "INVALID_LENGTH", "迁移说明不能超过 500 字符");
        return normalized;
    }

    private static void requireActiveContent(Content content) {
        if (content.status() != ContentStatus.ACTIVE) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "CONTENT_ARCHIVED");
        }
    }

    private static void requireVersion(WorkItem item, long expectedVersion) {
        if (item.rowVersion() != expectedVersion)
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }

    private static void requireWritableAccess(ProjectAccessSnapshot.ActorProjectAccess actorAccess) {
        if (actorAccess == ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }

    private static void requireWritableAccess(ProjectFactWriteSnapshot.ActorProjectAccess actorAccess) {
        if (actorAccess == ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }

    private static void requireActor(CurrentActor actor) {
        if (actor == null)
            throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }

    private record VisibleContent(ProjectAccessSnapshot project, Content content) {}
}
