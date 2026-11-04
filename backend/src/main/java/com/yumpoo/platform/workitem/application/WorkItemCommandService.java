package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.content.ContentRepository;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelModels;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelRepository;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.collaboration.CollaborationHtmlSanitizer;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ProjectSortKey;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.springframework.stereotype.Service;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.yumpoo.platform.workitem.application.WorkItemViews.VisibleContent;

import static com.yumpoo.platform.workitem.application.WorkItemCommands.Create;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.CreateSubitem;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.ChangeContent;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.InlineUpdate;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Transition;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Update;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedProjectWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemRelationRepository.ParentChildRelation;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.FIELDS_CHANGED;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.changed;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.commonEventPayload;
import static com.yumpoo.platform.workitem.application.WorkItemRules.INVALID_WORK_ITEM_MESSAGE;
import static com.yumpoo.platform.workitem.application.WorkItemRules.normalizeResolution;
import static com.yumpoo.platform.workitem.application.WorkItemRules.priority;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requestedAssignees;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireActiveContent;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireActor;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireDateRange;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireDistinctStatus;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireSelectablePriority;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireVersion;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireWritableAccess;
import static com.yumpoo.platform.workitem.application.WorkItemRules.validation;
import static com.yumpoo.platform.workitem.application.WorkItemViews.selectableStatus;

@Service
public class WorkItemCommandService {
    private final WorkItemRepository workItems;
    private final WorkItemRelationRepository relations;
    private final ContentRepository contents;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectActiveMembershipQuery activeMemberships;
    private final WorkItemLabelRepository labels;
    private final IdempotentCommandExecutor idempotency;
    private final Clock clock;
    private final CollaborationHtmlSanitizer sanitizer;
    private final WorkItemOrderingService ordering;
    private final WorkItemViews views;
    private final WorkItemEvents workItemEvents;

    public WorkItemCommandService(WorkItemRepository workItems, WorkItemRelationRepository relations, ContentRepository contents,
            ProjectFactWriteGuard writeGuard, ProjectActiveMembershipQuery activeMemberships, WorkItemLabelRepository labels,
            IdempotentCommandExecutor idempotency, Clock clock, CollaborationHtmlSanitizer sanitizer,
            WorkItemOrderingService ordering, WorkItemViews views, WorkItemEvents workItemEvents) {
        this.workItems = workItems;
        this.relations = relations;
        this.contents = contents;
        this.writeGuard = writeGuard;
        this.activeMemberships = activeMemberships;
        this.labels = labels;
        this.idempotency = idempotency;
        this.clock = clock;
        this.sanitizer = sanitizer;
        this.ordering = ordering;
        this.views = views;
        this.workItemEvents = workItemEvents;
    }

    public IdempotencyExecutionResult create(Create command) {
        VisibleContent visible = views.visibleContent(command.actor(), command.contentId());
        if (!visible.project().projectId().equals(command.projectId())) {
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
        requireWritableAccess(visible.project().actorAccess());
        String priority = priority(command.priority());
        String description = description(command.description());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "createWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                    command.actor(), visible.project().projectId());
            requireWritableAccess(project.actorAccess());
            Content content = lockCreationContent(project.companyId(), project.projectId(),
                            command.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireActiveContent(content);
            CreatedWorkItem created = createItem(ItemWriteTarget.from(project), content, new WorkItemDraft(
                    command.title(), priority, resolveAssignees(command.assigneeUserIds(), command.assigneeUserId(), null),
                    command.assigneeUserIds() == null ? "assigneeUserId" : "assigneeUserIds", description,
                    command.notes(), command.timelineStartDate(), command.timelineEndDate(),
                    command.dueDate(), command.dueTime()), command.actor());
            return views.stored(201, views.detail(created.item(),
                    views.people(project.companyId(), List.of(created.item())), true,
                    created.statusLabels()));
        });
    }

    public IdempotencyExecutionResult createSubitem(CreateSubitem command) {
        requireActor(command.actor());
        WorkItemLocator parentLocator = workItems.findLocator(command.actor().companyId(),
                        command.parentWorkItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot visible = views.visible(command.actor(), parentLocator.projectId());
        requireWritableAccess(visible.actorAccess());
        String priority = priority(command.priority());
        String description = description(command.description());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "createWorkItemSubitem",
                command.idempotencyKey()), command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                    command.actor(), parentLocator.projectId());
            requireWritableAccess(project.actorAccess());
            Content targetContent = lockCreationContent(project.companyId(), project.projectId(), command.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireActiveContent(targetContent);
            Content parentContent = parentLocator.contentId().equals(targetContent.id())
                    ? targetContent : contents.lockForShare(project.companyId(), project.projectId(),
                            parentLocator.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            WorkItem parent = workItems.lock(project.companyId(), project.projectId(),
                            parentContent.id(), command.parentWorkItemId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            if (relations.hasActiveParent(project.companyId(), parent.id())) {
                throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "NESTED_SUBITEM_NOT_SUPPORTED");
            }
            CreatedWorkItem created = createItem(ItemWriteTarget.from(project), targetContent, new WorkItemDraft(
                    command.title(), priority, resolveAssignees(command.assigneeUserIds(), command.assigneeUserId(), null),
                    command.assigneeUserIds() == null ? "assigneeUserId" : "assigneeUserIds", description,
                    command.notes(), command.timelineStartDate(), command.timelineEndDate(),
                    command.dueDate(), command.dueTime()), command.actor());
            ParentChildRelation relation = new ParentChildRelation(UUID.randomUUID(),
                    project.companyId(), parent.id(), created.item().id(), project.projectId(),
                    command.actor().userId(), created.item().createdAt());
            if (!relations.insertParentChild(relation))
                throw new IllegalStateException("work item parent-child relation insert failed");
            workItemEvents.appendRelationCreated(relation, command.actor());
            return views.stored(201, views.detail(created.item(),
                    views.people(project.companyId(), List.of(created.item())), true,
                    created.statusLabels()));
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkItem createRootItemForConnection(ItemWriteTarget target, UUID contentId,
            String title, CurrentActor actor) {
        requireActor(actor);
        if (!target.companyId().equals(actor.companyId()))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        Content content = lockCreationContent(target.companyId(), target.projectId(), contentId)
                .orElseThrow(() -> validation("contentId", "CONTENT_NOT_AVAILABLE", "类别不属于目标项目或已删除"));
        if (!content.active() || content.deletedAt() != null)
            throw validation("contentId", "CONTENT_NOT_ACTIVE", "请选择目标项目的启用类别");
        return createItem(target, content, new WorkItemDraft(title, null, List.of(), "assigneeUserIds", null, null,
                null, null, null, DueTimeChange.unchanged()), actor).item();
    }

    Optional<Content> lockCreationContent(UUID companyId, UUID projectId, UUID contentId) {
        var snapshot = contents.find(companyId, projectId, contentId);
        if (snapshot.isEmpty()) return Optional.empty();
        // First-use writers must wait for the catalog without holding a category share lock.
        if (!snapshot.get().everUsed()) contents.lockCatalogVersion(companyId, projectId);
        return contents.lockForShare(companyId, projectId, contentId);
    }

    private CreatedWorkItem createItem(ItemWriteTarget project, Content content,
            WorkItemDraft draft, CurrentActor actor) {
        markContentUsed(project, content, actor);
        requireActiveAssignees(project, draft.assigneeUserIds(), draft.assigneeField());
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        WorkItemLabelModels.StatusLabel initial = statusLabels.stream()
                .filter(label -> label.code().equals("NOT_STARTED") && label.active()).findFirst()
                .orElseThrow(() -> ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION,
                        "PROJECT_INITIAL_STATUS_MISSING"));
        requireSelectablePriority(draft.priority(), priorityLabels, null);
        workItems.lockRankLanes(project.companyId(), project.projectId(), List.of(initial.code()));
        String rank = ordering.allocateRank(project.companyId(), project.projectId(),
                initial.code(), WorkItemRankPlacement.START, null, null).rank();
        UUID workItemId = UUID.randomUUID();
        workItems.lockProjectOrder(project.companyId(), project.projectId());
        String projectSortKey = ProjectSortKey.between(null,
                        workItems.findFirstProjectRank(project.companyId(), project.projectId(),
                                workItemId).map(RankedProjectWorkItem::rank).orElse(null))
                .orElseThrow(() -> ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ORDER_DENSE"));
        long sequence = workItems.nextSequence(project.companyId(), project.projectId());
        WorkItem item;
        Instant now = clock.instant();
        try {
            item = WorkItem.create(workItemId, project.companyId(), project.projectId(),
                    content.id(), sequence, project.projectCode() + "-" + sequence,
                    draft.title(), initial.code(),
                    WorkItemStatusCategory.valueOf(initial.statusCategory()), draft.priority(),
                    null, draft.description(), draft.notes(),
                    draft.timelineStartDate(), draft.timelineEndDate(), draft.dueDate(),
                    draft.dueTime().resolve(draft.dueDate(), null), rank,
                    projectSortKey, actor.userId(), now).assign(draft.assigneeUserIds(), actor.userId(), now);
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", INVALID_WORK_ITEM_MESSAGE);
        }
        if (!workItems.insert(item)) throw new IllegalStateException("work item insert failed");
        workItemEvents.appendCreated(item, actor);
        return new CreatedWorkItem(item, statusLabels);
    }

    private record WorkItemDraft(String title, String priority, List<UUID> assigneeUserIds,
            String assigneeField, String description, String notes, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate, DueTimeChange dueTime) {}

    private record CreatedWorkItem(WorkItem item,
            List<WorkItemLabelModels.StatusLabel> statusLabels) {}

    void markContentUsed(ItemWriteTarget project, Content content, CurrentActor actor) {
        if (content.everUsed()) return;
        long catalogVersion = contents.lockCatalogVersion(project.companyId(), project.projectId());
        Content locked = contents.lock(project.companyId(), project.projectId(), content.id())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (locked.everUsed()) return;
        contents.update(locked.markUsed(actor.userId(), clock.instant()), locked.rowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        if (!contents.bumpCatalogVersion(project.companyId(), project.projectId(),
                catalogVersion, clock.instant())) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
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
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        List<UUID> assignees = resolveAssignees(command.assigneeUserIds(), command.assigneeUserId(), before);
        requireActiveAssignees(project, assignees.stream().filter(id -> !before.assigneeUserIds().contains(id)).toList(),
                command.assigneeUserIds() == null ? "assigneeUserId" : "assigneeUserIds");
        String description = description(command.description());
        WorkItem candidate;
        try {
            String nextPriority = priority(command.priority());
            requireSelectablePriority(nextPriority, priorityLabels, before.priority());
            candidate = before.updateFields(command.title(), nextPriority,
                    before.assigneeUserId(), description, command.notes(),
                    command.timelineStartDate(), command.timelineEndDate(), command.dueDate(),
                    command.dueTime().resolve(command.dueDate(), before.dueTime()),
                    command.actor().userId(), clock.instant());
            candidate = candidate.assign(assignees, command.actor().userId(), candidate.updatedAt());
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", INVALID_WORK_ITEM_MESSAGE);
        }
        List<String> changedFields = changed(before, candidate);
        if (changedFields.isEmpty()) {
            return views.detail(before, views.people(project.companyId(), List.of(before)), true, statusLabels);
        }
        if (!before.assigneeUserIds().equals(candidate.assigneeUserIds())) workItems.replaceAssignees(candidate);
        WorkItem after = workItems.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        workItemEvents.appendFieldsChanged(before, after, command.actor(), changedFields);
        workItemEvents.appendAssignmentChange(before, after, command.actor());
        workItemEvents.appendAssigneesChange(before, after, command.actor());
        return views.detail(after, views.people(project.companyId(), List.of(after)), true, statusLabels);
    }

    public IdempotencyExecutionResult transition(Transition command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
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
        WorkItem snapshot = workItems.find(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(snapshot, command.expectedVersion());
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        WorkItemLabelModels.StatusLabel target = selectableStatus(statusLabels, command.toStatus());
        requireDistinctStatus(snapshot.statusCode(), target.code());
        String resolution = normalizeResolution(command.resolution());
        workItems.lockRankLanes(project.companyId(), project.projectId(),
                List.of(snapshot.statusCode(), target.code()));
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        requireDistinctStatus(before.statusCode(), target.code());
        String rank = ordering.allocateRank(project.companyId(), project.projectId(),
                target.code(), WorkItemRankPlacement.START, null, before.id()).rank();
        WorkItem candidate;
        try {
            candidate = before.move(target.code(),
                    WorkItemStatusCategory.valueOf(target.statusCategory()), rank,
                    command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "WORK_ITEM_TRANSITION_REJECTED");
        }
        WorkItem after = workItems.transition(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        workItemEvents.appendStatusChanged(before, after, command.actor(), resolution);
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
                statusLabels));
    }

    public IdempotencyExecutionResult inlineUpdate(InlineUpdate command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "PATCH", "inlineUpdateWorkItem:" + command.field(),
                command.idempotencyKey()), command.requestHash()),
                () -> inlineUpdate(command, locator));
    }

    public IdempotencyExecutionResult changeContent(ChangeContent command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "PATCH", "changeWorkItemContent",
                command.idempotencyKey()), command.requestHash()),
                () -> changeContent(command, locator));
    }

    private StoredCommandResult changeContent(ChangeContent command, WorkItemLocator locator) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        Content previous = contents.find(project.companyId(), project.projectId(), before.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (before.contentId().equals(command.contentId())) {
            return views.stored(200, views.detail(before, views.people(project.companyId(), List.of(before)), true,
                    labels.statuses(project.companyId(), project.projectId())));
        }
        long catalogVersion = contents.lockCatalogVersion(project.companyId(), project.projectId());
        Content target = contents.lock(project.companyId(), project.projectId(), command.contentId())
                .orElseThrow(() -> validation("contentId", "UNKNOWN_CONTENT", "工作项类别不存在"));
        requireActiveContent(target);
        if (!target.everUsed()) {
            target = contents.update(target.markUsed(command.actor().userId(), clock.instant()),
                            target.rowVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            if (!contents.bumpCatalogVersion(project.companyId(), project.projectId(),
                    catalogVersion, clock.instant())) {
                throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            }
        }
        WorkItem candidate = before.changeContent(target.id(), command.actor().userId(), clock.instant());
        WorkItem after = workItems.changeContent(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        Map<String, Object> payload = commonEventPayload(after);
        payload.put("changedFields", List.of("contentId"));
        payload.put("previousContentId", previous.id());
        payload.put("previousContentName", previous.name());
        payload.put("previousContentColorToken", previous.colorToken());
        payload.put("contentName", target.name());
        payload.put("contentColorToken", target.colorToken());
        workItemEvents.append(FIELDS_CHANGED, after, command.actor(), payload);
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
                labels.statuses(project.companyId(), project.projectId())));
    }

    private StoredCommandResult inlineUpdate(InlineUpdate command, WorkItemLocator locator) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(),
                        locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem before = workItems.lock(project.companyId(), project.projectId(), content.id(),
                        command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        List<UUID> assignees = "ASSIGNEES".equals(command.field())
                ? resolveAssignees(command.assigneeUserIds(), null, null)
                : "ASSIGNEE".equals(command.field()) ? resolveAssignees(null, command.assigneeUserId(), null)
                : before.assigneeUserIds();
        if ("ASSIGNEE".equals(command.field()) || "ASSIGNEES".equals(command.field()))
            requireActiveAssignees(project, assignees.stream().filter(id -> !before.assigneeUserIds().contains(id)).toList(),
                    "ASSIGNEES".equals(command.field()) ? "assigneeUserIds" : "assigneeUserId");
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        String nextPriority = "PRIORITY".equals(command.field())
                ? priority(command.priority()) : before.priority();
        if ("PRIORITY".equals(command.field()))
            requireSelectablePriority(nextPriority, priorityLabels, before.priority());
        WorkItem candidate;
        try {
            candidate = before.updateFields(before.title(),
                    nextPriority,
                    before.assigneeUserId(),
                    "DESCRIPTION".equals(command.field()) ? description(command.description()) : before.description(),
                    before.notes(), before.timelineStartDate(),
                    before.timelineEndDate(),
                    "DUE_DATE".equals(command.field()) ? command.dueDate() : before.dueDate(),
                    "DUE_DATE".equals(command.field())
                            ? command.dueTime().resolve(command.dueDate(), before.dueTime()) : before.dueTime(),
                    command.actor().userId(), clock.instant());
            candidate = candidate.assign(assignees, command.actor().userId(), candidate.updatedAt());
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", INVALID_WORK_ITEM_MESSAGE);
        }
        List<String> changedFields = changed(before, candidate);
        if (changedFields.isEmpty())
            return views.stored(200, views.detail(before, views.people(project.companyId(), List.of(before)), true,
                    statusLabels));
        if (!before.assigneeUserIds().equals(candidate.assigneeUserIds())) workItems.replaceAssignees(candidate);
        WorkItem after = workItems.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        workItemEvents.appendFieldsChanged(before, after, command.actor(), changedFields);
        workItemEvents.appendAssignmentChange(before, after, command.actor());
        workItemEvents.appendAssigneesChange(before, after, command.actor());
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
                statusLabels));
    }

    private List<UUID> resolveAssignees(List<UUID> requestIds, UUID legacyId, WorkItem current) {
        List<UUID> requested = requestedAssignees(requestIds);
        if (requested != null) return requested;
        return current != null && legacyId != null && legacyId.equals(current.assigneeUserId())
                ? current.assigneeUserIds() : legacyId == null ? List.of() : List.of(legacyId);
    }

    private void requireActiveAssignees(ProjectFactWriteSnapshot project, List<UUID> ids, String field) {
        requireActiveAssignees(ItemWriteTarget.from(project), ids, field);
    }

    private void requireActiveAssignees(ItemWriteTarget project, List<UUID> ids, String field) {
        if (!ids.isEmpty() && !activeMemberships.findActiveMemberIds(project.companyId(), project.projectId(), ids).containsAll(ids))
            throw validation(field, "NOT_ACTIVE_PROJECT_MEMBER", "处理人必须是当前 Project 的 ACTIVE 成员");
    }

    private String description(String untrustedHtml) {
        try {
            return sanitizer.sanitizeDescription(untrustedHtml);
        } catch (IllegalArgumentException exception) {
            throw validation("description", "INVALID_DESCRIPTION", "描述格式无效或超过长度上限");
        }
    }
}
