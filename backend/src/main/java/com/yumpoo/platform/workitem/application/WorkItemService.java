package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.api.pagination.CursorPageRequest;
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
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ContentStatus;
import com.yumpoo.platform.workitem.domain.ContentViewType;
import com.yumpoo.platform.workitem.domain.KanbanRank;
import com.yumpoo.platform.workitem.domain.ProjectSortKey;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemCommands.Create;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.CreateSubitem;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Delete;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.RankMove;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.ProjectOrderMove;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.SubitemOrderMove;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.InlineUpdate;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Restore;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Transition;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Update;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedProjectWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemRelationRepository.ParentChildRelation;

@Service
public class WorkItemService {
    private static final String CREATED = "workitem.work_item_created";
    private static final String FIELDS_CHANGED = "workitem.work_item_fields_changed";
    private static final String ASSIGNED = "workitem.work_item_assigned";
    private static final String UNASSIGNED = "workitem.work_item_unassigned";
    private static final String STATUS_CHANGED = "workitem.work_item_status_changed";
    private static final String RANK_CHANGED = "workitem.work_item_rank_changed";
    private static final String DELETED = "workitem.work_item_deleted";
    private static final String RESTORED = "workitem.work_item_restored";
    private static final String RELATION_CREATED = "workitem.work_item_relation_created";

    private final WorkItemRepository workItems;
    private final WorkItemRelationRepository relations;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectActiveMembershipQuery activeMemberships;
    private final WorkItemLabelRepository labels;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProjectWorkItemCursorCodec projectCursors = new ProjectWorkItemCursorCodec();
    private final ProjectWorkItemFilterCursorCodec projectFilterCursors =
            new ProjectWorkItemFilterCursorCodec();

    public WorkItemService(WorkItemRepository workItems, WorkItemRelationRepository relations,
            ContentRepository contents,
            ProjectAccessSnapshotQuery access, ProjectFactWriteGuard writeGuard,
            ProjectActiveMembershipQuery activeMemberships,
            WorkItemLabelRepository labels, MinimalUserSnapshotQuery users,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events,
            ObjectMapper objectMapper, Clock clock) {
        this.workItems = workItems;
        this.relations = relations;
        this.contents = contents;
        this.access = access;
        this.writeGuard = writeGuard;
        this.activeMemberships = activeMemberships;
        this.labels = labels;
        this.users = users;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkItemPage list(CurrentActor actor, UUID contentId,
            WorkItemQuery.Request request, String view, OffsetPageRequest page) {
        VisibleContent visible = visibleContent(actor, contentId);
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                visible.project().companyId(), visible.project().projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                visible.project().companyId(), visible.project().projectId());
        Set<String> allowedStatuses = statusLabels.stream().map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        ContentViewType effectiveView = view(view);
        if (effectiveView == ContentViewType.KANBAN) {
            if (query.statuses().size() != 1)
                throw validation("status", "KANBAN_REQUIRES_ONE_STATUS",
                        "Kanban 泳道查询必须且只能指定一个状态");
            if (request.sorts() != null && !request.sorts().isEmpty())
                throw validation("sort", "KANBAN_SORT_FIXED", "Kanban 固定按 rank 排序");
        }
        WorkItemSortRanks ranks = sortRanks(visible.project().companyId(),
                visible.project().projectId(), contentId, query, statusLabels, priorityLabels);
        List<WorkItem> rows = workItems.findPage(visible.project().companyId(),
                visible.project().projectId(), contentId, query, ranks, effectiveView, page);
        long total = workItems.countPage(visible.project().companyId(),
                visible.project().projectId(), contentId, query);
        Map<UUID, MinimalUserSnapshot> people = people(visible.project().companyId(), rows);
        OffsetPageResponse<WorkItemSummary> response = OffsetPageResponse.of(rows.stream()
                .map(item -> summary(item, people, canEdit(visible.project(), visible.content()),
                        statusLabels)).toList(), page, total);
        return new WorkItemPage(response.items(), response.page(), response.size(),
                response.totalElements(), response.totalPages());
    }

    @Transactional(readOnly = true)
    public ProjectWorkItemCursorPage listProject(CurrentActor actor, UUID projectId,
            WorkItemQuery.Request request, String view, CursorPageRequest page) {
        requireActor(actor);
        ProjectAccessSnapshot project = visible(actor, projectId);
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        Set<String> allowedStatuses = statusLabels.stream().map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        ContentViewType effectiveView = view(view);
        if (effectiveView == ContentViewType.KANBAN) {
            if (query.statuses().size() != 1)
                throw validation("status", "KANBAN_REQUIRES_ONE_STATUS",
                        "项目 Kanban 泳道查询必须且只能指定一个状态");
            if (request.sorts() != null && !request.sorts().isEmpty())
                throw validation("sort", "KANBAN_SORT_FIXED",
                        "项目 Kanban 固定按更新时间倒序");
        }
        WorkItemSortRanks ranks = sortRanks(project.companyId(), project.projectId(),
                null, query, statusLabels, priorityLabels);
        String fingerprint = projectCursorFingerprint(projectId, effectiveView, query);
        ProjectWorkItemCursorCodec.Cursor decoded = projectCursors.decode(page.cursor());
        if (decoded != null && (!decoded.fingerprint().equals(fingerprint)
                || decoded.view() != effectiveView))
            throw validation("cursor", "CURSOR_QUERY_MISMATCH", "游标不属于当前项目查询");
        WorkItemRepository.ProjectCursorAnchor anchor = decoded == null ? null : decoded.anchor();
        List<WorkItem> rows = new ArrayList<>(workItems.findProjectCursorPage(project.companyId(),
                project.projectId(), query, ranks, effectiveView, anchor, page.limit() + 1));
        boolean hasMore = rows.size() > page.limit();
        if (hasMore) rows = new ArrayList<>(rows.subList(0, page.limit()));
        Map<UUID, Content> contentById = new LinkedHashMap<>();
        contents.findAll(project.companyId(), project.projectId())
                .forEach(content -> contentById.put(content.id(), content));
        Map<UUID, MinimalUserSnapshot> people = people(project.companyId(), rows);
        Map<UUID, Long> subitemCounts = relations.countActiveChildren(project.companyId(),
                rows.stream().map(WorkItem::id).toList());
        List<ProjectWorkItemListItem> items = rows.stream()
                .map(item -> projectListItem(item, contentById.get(item.contentId()), people,
                        canEdit(project, contentById.get(item.contentId())), statusLabels,
                        subitemCounts.getOrDefault(item.id(), 0L))).toList();
        String nextCursor = hasMore && !rows.isEmpty()
                ? projectCursors.encode(new ProjectWorkItemCursorCodec.Cursor(
                        fingerprint, effectiveView,
                        WorkItemRepository.ProjectCursorAnchor.from(rows.getLast()))) : null;
        return new ProjectWorkItemCursorPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public WorkItemSubitemList listSubitems(CurrentActor actor, UUID parentWorkItemId,
            WorkItemQuery.Request request) {
        requireActor(actor);
        WorkItemLocator locator = workItems.findLocator(actor.companyId(), parentWorkItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        workItems.find(project.companyId(), project.projectId(), locator.contentId(),
                        parentWorkItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        Set<String> allowedStatuses = statusLabels.stream()
                .map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        WorkItemSortRanks ranks = sortRanks(project.companyId(), project.projectId(), null,
                query, statusLabels, priorityLabels);
        List<WorkItem> rows = workItems.findSubitems(project.companyId(), project.projectId(),
                parentWorkItemId, query, ranks);
        Map<UUID, Content> contentById = new LinkedHashMap<>();
        contents.findAll(project.companyId(), project.projectId())
                .forEach(content -> contentById.put(content.id(), content));
        Map<UUID, MinimalUserSnapshot> people = people(project.companyId(), rows);
        Map<UUID, Long> subitemCounts = relations.countActiveChildren(project.companyId(),
                rows.stream().map(WorkItem::id).toList());
        return new WorkItemSubitemList(rows.stream().map(item -> projectListItem(item,
                contentById.get(item.contentId()), people,
                canEdit(project, contentById.get(item.contentId())), statusLabels,
                subitemCounts.getOrDefault(item.id(), 0L))).toList());
    }

    @Transactional(readOnly = true)
    public ProjectWorkItemFilterOptionCursorPage listProjectFilterOptions(CurrentActor actor,
            UUID projectId, String field, WorkItemQuery.Request request, CursorPageRequest page) {
        requireActor(actor);
        ProjectAccessSnapshot project = visible(actor, projectId);
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        Set<String> allowedStatuses = statusLabels.stream().map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        String normalizedField;
        try {
            normalizedField = Objects.requireNonNull(field).strip().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("TITLE", "ASSIGNEE", "STATUS", "PRIORITY", "CONTENT",
                    "DUE_DATE", "UPDATED_AT").contains(normalizedField)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw validation("field", "INVALID_VALUE", "筛选选项字段不受支持");
        }
        String fingerprint = projectCursorFingerprint(projectId, ContentViewType.TABLE, query)
                + ":" + normalizedField;
        ProjectWorkItemFilterCursorCodec.Cursor decoded = projectFilterCursors.decode(page.cursor());
        if (decoded != null && (!decoded.fingerprint().equals(fingerprint)
                || !decoded.field().equals(normalizedField)))
            throw validation("cursor", "CURSOR_QUERY_MISMATCH", "筛选选项游标不属于当前查询");
        List<WorkItemRepository.FilterOptionCount> rows = new ArrayList<>(
                workItems.findProjectFilterOptions(project.companyId(), project.projectId(), query,
                        normalizedField, decoded == null ? null : decoded.lastValue(), page.limit() + 1));
        boolean hasMore = rows.size() > page.limit();
        if (hasMore) rows = new ArrayList<>(rows.subList(0, page.limit()));
        Map<UUID, String> contentNames = contents.findAll(project.companyId(), project.projectId())
                .stream().collect(java.util.stream.Collectors.toMap(Content::id, Content::name));
        Set<UUID> userIds = rows.stream().filter(row -> "ASSIGNEE".equals(normalizedField))
                .map(WorkItemRepository.FilterOptionCount::value)
                .filter(value -> !"__NULL__".equals(value)).map(UUID::fromString)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, MinimalUserSnapshot> people = users.findByUserIds(project.companyId(), userIds);
        Map<String, String> statusNames = statusLabels.stream().collect(
                java.util.stream.Collectors.toMap(WorkItemLabelModels.StatusLabel::code,
                        WorkItemLabelModels.StatusLabel::displayName));
        Map<String, String> priorityNames = priorityLabels.stream().collect(
                java.util.stream.Collectors.toMap(WorkItemLabelModels.PriorityLabel::code,
                        WorkItemLabelModels.PriorityLabel::displayName));
        List<ProjectWorkItemFilterOption> items = rows.stream().map(row -> {
            String label = switch (normalizedField) {
                case "CONTENT" -> contentNames.getOrDefault(UUID.fromString(row.value()), row.value());
                case "ASSIGNEE" -> "__NULL__".equals(row.value()) ? "未分配"
                        : Optional.ofNullable(people.get(UUID.fromString(row.value())))
                                .map(MinimalUserSnapshot::displayName).orElse("未知成员");
                case "STATUS" -> statusNames.getOrDefault(row.value(), row.value());
                case "PRIORITY" -> "__NULL__".equals(row.value()) ? "未设置"
                        : priorityNames.getOrDefault(row.value(), row.value());
                case "DUE_DATE" -> "__NULL__".equals(row.value()) ? "未设置" : row.value();
                default -> row.value();
            };
            return new ProjectWorkItemFilterOption(row.value(), label, row.count());
        }).toList();
        String nextCursor = hasMore && !rows.isEmpty() ? projectFilterCursors.encode(
                new ProjectWorkItemFilterCursorCodec.Cursor(fingerprint, normalizedField,
                        rows.getLast().value())) : null;
        return new ProjectWorkItemFilterOptionCursorPage(items, nextCursor);
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
                labels.statuses(project.companyId(), project.projectId()));
    }

    @Transactional(readOnly = true)
    public WorkItemDetail findForLifecycle(CurrentActor actor, UUID workItemId) {
        requireActor(actor);
        WorkItemLocator locator = workItems.findLocatorIncludingDeleted(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems.findIncludingDeleted(project.companyId(), project.projectId(),
                        locator.contentId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return detail(item, people(project.companyId(), List.of(item)), canEdit(project, content),
                labels.statuses(project.companyId(), project.projectId()));
    }

    public IdempotencyExecutionResult create(Create command) {
        VisibleContent visible = visibleContent(command.actor(), command.contentId());
        requireWritableAccess(visible.project().actorAccess());
        String priority = priority(command.priority());
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
            CreatedWorkItem created = createItem(project, content, new WorkItemDraft(
                    command.title(), priority, command.assigneeUserId(), command.description(),
                    command.notes(), command.timelineStartDate(), command.timelineEndDate(),
                    command.dueDate()), command.actor());
            return stored(201, detail(created.item(),
                    people(project.companyId(), List.of(created.item())), true,
                    created.statusLabels()));
        });
    }

    public IdempotencyExecutionResult createSubitem(CreateSubitem command) {
        requireActor(command.actor());
        WorkItemLocator parentLocator = workItems.findLocator(command.actor().companyId(),
                        command.parentWorkItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot visible = visible(command.actor(), parentLocator.projectId());
        requireWritableAccess(visible.actorAccess());
        String priority = priority(command.priority());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "createWorkItemSubitem",
                command.idempotencyKey()), command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                    command.actor(), parentLocator.projectId());
            requireWritableAccess(project.actorAccess());
            Content parentContent = contents.lockForShare(project.companyId(), project.projectId(),
                            parentLocator.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireActiveContent(parentContent);
            WorkItem parent = workItems.lock(project.companyId(), project.projectId(),
                            parentContent.id(), command.parentWorkItemId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            if (relations.hasActiveParent(project.companyId(), parent.id())) {
                throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "NESTED_SUBITEM_NOT_SUPPORTED");
            }
            Content targetContent = parentContent.id().equals(command.contentId())
                    ? parentContent
                    : contents.lockForShare(project.companyId(), project.projectId(), command.contentId())
                            .orElseThrow(() -> new ApplicationException(
                                    StandardErrorCode.RESOURCE_NOT_FOUND));
            requireActiveContent(targetContent);
            CreatedWorkItem created = createItem(project, targetContent, new WorkItemDraft(
                    command.title(), priority, command.assigneeUserId(), command.description(),
                    command.notes(), command.timelineStartDate(), command.timelineEndDate(),
                    command.dueDate()), command.actor());
            ParentChildRelation relation = new ParentChildRelation(UUID.randomUUID(),
                    project.companyId(), parent.id(), created.item().id(), project.projectId(),
                    command.actor().userId(), created.item().createdAt());
            if (!relations.insertParentChild(relation))
                throw new IllegalStateException("work item parent-child relation insert failed");
            appendRelationCreated(relation, command.actor());
            return stored(201, detail(created.item(),
                    people(project.companyId(), List.of(created.item())), true,
                    created.statusLabels()));
        });
    }

    private CreatedWorkItem createItem(ProjectFactWriteSnapshot project, Content content,
            WorkItemDraft draft, CurrentActor actor) {
        requireActiveAssignee(project, draft.assigneeUserId());
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
        workItems.lockRankLanes(content.id(), List.of(initial.code()));
        String rank = allocateRank(project.companyId(), project.projectId(), content.id(),
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
        try {
            item = WorkItem.create(workItemId, project.companyId(), project.projectId(),
                    content.id(), sequence, project.projectCode() + "-" + sequence,
                    content.workItemType(), draft.title(), initial.code(),
                    WorkItemStatusCategory.valueOf(initial.statusCategory()), draft.priority(),
                    draft.assigneeUserId(), draft.description(), draft.notes(),
                    draft.timelineStartDate(), draft.timelineEndDate(), draft.dueDate(), rank,
                    projectSortKey, actor.userId(), clock.instant());
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", exception.getMessage());
        }
        if (!workItems.insert(item)) throw new IllegalStateException("work item insert failed");
        appendCreated(item, actor);
        return new CreatedWorkItem(item, statusLabels);
    }

    private record WorkItemDraft(String title, String priority, UUID assigneeUserId,
            String description, String notes, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate) {}

    private record CreatedWorkItem(WorkItem item,
            List<WorkItemLabelModels.StatusLabel> statusLabels) {}

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
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                project.companyId(), project.projectId());
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        requireDateRange(command.timelineStartDate(), command.timelineEndDate());
        requireActiveAssignee(project, command.assigneeUserId());
        WorkItem candidate;
        try {
            String nextPriority = priority(command.priority());
            requireSelectablePriority(nextPriority, priorityLabels, before.priority());
            candidate = before.updateFields(command.title(), nextPriority,
                    command.assigneeUserId(), command.description(), command.notes(),
                    command.timelineStartDate(), command.timelineEndDate(), command.dueDate(),
                    command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", exception.getMessage());
        }
        List<String> changedFields = changed(before, candidate);
        if (changedFields.isEmpty()) {
            return detail(before, people(project.companyId(), List.of(before)), true, statusLabels);
        }
        WorkItem after = workItems.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        appendFieldsChanged(after, command.actor(), changedFields);
        appendAssignmentChange(before, after, command.actor());
        return detail(after, people(project.companyId(), List.of(after)), true, statusLabels);
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
        WorkItem snapshot = workItems.find(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(snapshot, command.expectedVersion());
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        WorkItemLabelModels.StatusLabel target = selectableStatus(statusLabels, command.toStatus());
        requireDistinctStatus(snapshot.statusCode(), target.code());
        String resolution = normalizeResolution(command.resolution());
        workItems.lockRankLanes(content.id(), List.of(snapshot.statusCode(), target.code()));
        WorkItem before = workItems.lock(project.companyId(), project.projectId(),
                        locator.contentId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        requireDistinctStatus(before.statusCode(), target.code());
        String rank = allocateRank(project.companyId(), project.projectId(), content.id(),
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
        appendStatusChanged(before, after, command.actor(), resolution);
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true,
                statusLabels));
    }

    public IdempotencyExecutionResult rankMove(RankMove command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "rankMoveWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> rankMove(command, locator));
    }

    public IdempotencyExecutionResult projectOrderMove(ProjectOrderMove command) {
        requireActor(command.actor());
        if (command.previousVisibleWorkItemId() == null && command.nextVisibleWorkItemId() == null)
            throw validation("body", "ORDER_ANCHOR_REQUIRED", "必须提供至少一个可见相邻工作项");
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (!locator.projectId().equals(command.projectId()))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        ProjectAccessSnapshot project = visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "moveProjectWorkItemOrder",
                command.idempotencyKey()), command.requestHash()),
                () -> projectOrderMove(command.actor(), locator, command.workItemId(),
                        command.expectedVersion(), command.previousVisibleWorkItemId(),
                        command.nextVisibleWorkItemId(), null));
    }

    public IdempotencyExecutionResult subitemOrderMove(SubitemOrderMove command) {
        requireActor(command.actor());
        if (command.previousVisibleWorkItemId() == null && command.nextVisibleWorkItemId() == null)
            throw validation("body", "ORDER_ANCHOR_REQUIRED", "必须提供至少一个可见相邻子项");
        WorkItemLocator parent = workItems.findLocator(command.actor().companyId(),
                        command.parentWorkItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItemLocator child = workItems.findLocator(command.actor().companyId(), command.subitemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (!parent.projectId().equals(child.projectId()))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        ProjectAccessSnapshot project = visible(command.actor(), parent.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "moveWorkItemSubitemOrder",
                command.idempotencyKey()), command.requestHash()),
                () -> projectOrderMove(command.actor(), child, command.subitemId(),
                        command.expectedVersion(), command.previousVisibleWorkItemId(),
                        command.nextVisibleWorkItemId(), command.parentWorkItemId()));
    }

    public IdempotencyExecutionResult inlineUpdate(InlineUpdate command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "PATCH", "inlineUpdateWorkItem:" + command.field(),
                command.idempotencyKey()), command.requestHash()),
                () -> inlineUpdate(command, locator));
    }

    public IdempotencyExecutionResult delete(Delete command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocatorIncludingDeleted(
                        command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        String reason = normalizeDeleteReason(command.reason());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "DELETE", "deleteWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> delete(command, locator, reason));
    }

    private StoredCommandResult delete(Delete command, WorkItemLocator locator, String reason) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        WorkItem before = workItems.lockIncludingDeleted(project.companyId(), project.projectId(),
                        content.id(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        if (before.deleted()) throw invalidLifecycle("WORK_ITEM_ALREADY_DELETED");
        WorkItem candidate;
        try {
            candidate = before.softDelete(reason, command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidLifecycle("WORK_ITEM_DELETE_REJECTED");
        }
        WorkItem after = workItems.softDelete(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        appendDeleted(after, command.actor());
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true,
                labels.statuses(project.companyId(), project.projectId())));
    }

    public IdempotencyExecutionResult restore(Restore command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocatorIncludingDeleted(
                        command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "restoreWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> restore(command, locator));
    }

    private StoredCommandResult restore(Restore command, WorkItemLocator locator) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        WorkItem snapshot = workItems.findIncludingDeleted(project.companyId(), project.projectId(),
                        content.id(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(snapshot, command.expectedVersion());
        if (!snapshot.deleted()) throw invalidLifecycle("WORK_ITEM_NOT_DELETED");
        workItems.lockRankLanes(content.id(), List.of(snapshot.statusCode()));
        WorkItem before = workItems.lockIncludingDeleted(project.companyId(), project.projectId(),
                        content.id(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        if (!before.deleted()) throw invalidLifecycle("WORK_ITEM_NOT_DELETED");
        List<RankedWorkItem> lane = workItems.findRankOrder(project.companyId(), project.projectId(),
                content.id(), before.statusCode());
        boolean originalRankAvailable = lane.stream().noneMatch(item -> item.rank().equals(before.rank()));
        String rank = originalRankAvailable ? before.rank()
                : allocateRank(project.companyId(), project.projectId(), content.id(),
                        before.statusCode(), WorkItemRankPlacement.START, null, null).rank();
        workItems.lockProjectOrder(project.companyId(), project.projectId());
        String projectSortKey = before.projectSortKey();
        if (workItems.projectSortKeyOccupied(project.companyId(), project.projectId(),
                projectSortKey, before.id())) {
            projectSortKey = ProjectSortKey.between(null,
                            workItems.findFirstProjectRank(project.companyId(), project.projectId(),
                                    before.id()).map(RankedProjectWorkItem::rank).orElse(null))
                    .orElseThrow(() -> ApplicationException.withReason(
                            StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ORDER_DENSE"));
        }
        WorkItem candidate;
        try {
            candidate = before.restore(rank, projectSortKey, command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidLifecycle("WORK_ITEM_RESTORE_REJECTED");
        }
        WorkItem after = workItems.restore(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        appendRestored(after, command.actor());
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true,
                labels.statuses(project.companyId(), project.projectId())));
    }

    private StoredCommandResult rankMove(RankMove command, WorkItemLocator locator) {
        WorkItemRankPlacement placement = placement(command.placement(), command.anchorWorkItemId());
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(
                project.companyId(), project.projectId());
        WorkItem snapshot = workItems.find(project.companyId(), project.projectId(), content.id(),
                        command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(snapshot, command.expectedVersion());
        WorkItemLabelModels.StatusLabel target = snapshot.statusCode().equals(command.toStatus())
                ? status(statusLabels, command.toStatus())
                : selectableStatus(statusLabels, command.toStatus());
        String resolution = normalizeResolution(command.resolution());
        if (snapshot.statusCode().equals(target.code()) && resolution != null) {
            throw validation("resolution", "NOT_ALLOWED", "同状态排序不接受迁移说明");
        }
        workItems.lockRankLanes(content.id(), List.of(snapshot.statusCode(), target.code()));
        WorkItem before = workItems.lock(project.companyId(), project.projectId(), content.id(),
                        command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        if (!before.statusCode().equals(snapshot.statusCode()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        RankAllocation allocation = allocateRank(project.companyId(), project.projectId(),
                content.id(), target.code(), placement, command.anchorWorkItemId(), before.id());
        if (allocation.noOp())
            return stored(200, detail(before, people(project.companyId(), List.of(before)), true,
                    statusLabels));
        WorkItem candidate;
        try {
            candidate = before.statusCode().equals(target.code())
                    ? before.reorder(allocation.rank(), command.actor().userId(), clock.instant())
                    : before.move(target.code(),
                            WorkItemStatusCategory.valueOf(target.statusCategory()), allocation.rank(),
                            command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "WORK_ITEM_RANK_MOVE_REJECTED");
        }
        WorkItem after = workItems.transition(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        if (before.statusCode().equals(after.statusCode())) {
            appendRankChanged(after, command.actor(), placement, command.anchorWorkItemId());
        } else {
            appendStatusChanged(before, after, command.actor(), resolution);
        }
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true,
                statusLabels));
    }

    private StoredCommandResult projectOrderMove(CurrentActor actor, WorkItemLocator locator,
            UUID workItemId, long expectedVersion, UUID previousVisibleWorkItemId,
            UUID nextVisibleWorkItemId, UUID parentWorkItemId) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                actor, locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(),
                        locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        workItems.lockProjectOrder(project.companyId(), project.projectId());
        if (parentWorkItemId != null) {
            WorkItem parent = workItems.lockProjectItem(project.companyId(), project.projectId(),
                            parentWorkItemId)
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            if (relations.hasActiveParent(project.companyId(), parent.id()))
                throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "NESTED_SUBITEM_NOT_SUPPORTED");
        }
        WorkItem moving = workItems.lockProjectItem(project.companyId(), project.projectId(),
                        workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(moving, expectedVersion);
        if (!matchesProjectOrderScope(project.companyId(), parentWorkItemId, moving.id()))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        if (Objects.equals(previousVisibleWorkItemId, moving.id())
                || Objects.equals(nextVisibleWorkItemId, moving.id())
                || Objects.equals(previousVisibleWorkItemId, nextVisibleWorkItemId))
            throw invalidProjectOrderAnchor();
        WorkItem previous = previousVisibleWorkItemId == null ? null
                : workItems.lockProjectItem(project.companyId(), project.projectId(),
                                previousVisibleWorkItemId)
                        .orElseThrow(WorkItemService::invalidProjectOrderAnchor);
        WorkItem next = nextVisibleWorkItemId == null ? null
                : workItems.lockProjectItem(project.companyId(), project.projectId(),
                                nextVisibleWorkItemId)
                        .orElseThrow(WorkItemService::invalidProjectOrderAnchor);
        if ((previous != null && !matchesProjectOrderScope(project.companyId(), parentWorkItemId,
                previous.id())) || (next != null && !matchesProjectOrderScope(project.companyId(),
                parentWorkItemId, next.id()))) throw invalidProjectOrderAnchor();
        if (previous != null && next != null
                && previous.projectSortKey().compareTo(next.projectSortKey()) >= 0)
            throw invalidProjectOrderAnchor();

        String lower;
        String upper;
        if (previous != null) {
            lower = previous.projectSortKey();
            upper = workItems.findProjectNeighborAfter(project.companyId(), project.projectId(),
                            lower, moving.id()).map(RankedProjectWorkItem::rank).orElse(null);
        } else {
            upper = next.projectSortKey();
            lower = workItems.findProjectNeighborBefore(project.companyId(), project.projectId(),
                            upper, moving.id()).map(RankedProjectWorkItem::rank).orElse(null);
        }
        Optional<String> allocated = ProjectSortKey.between(lower, upper);
        if (allocated.isEmpty()) {
            rebalanceProjectOrder(project.companyId(), project.projectId(), moving.id(),
                    lower == null ? upper : lower);
            if (previous != null) {
                previous = workItems.lockProjectItem(project.companyId(), project.projectId(), previous.id())
                        .orElseThrow(WorkItemService::invalidProjectOrderAnchor);
                lower = previous.projectSortKey();
                upper = workItems.findProjectNeighborAfter(project.companyId(), project.projectId(),
                                lower, moving.id()).map(RankedProjectWorkItem::rank).orElse(null);
            } else {
                next = workItems.lockProjectItem(project.companyId(), project.projectId(), next.id())
                        .orElseThrow(WorkItemService::invalidProjectOrderAnchor);
                upper = next.projectSortKey();
                lower = workItems.findProjectNeighborBefore(project.companyId(), project.projectId(),
                                upper, moving.id()).map(RankedProjectWorkItem::rank).orElse(null);
            }
            allocated = ProjectSortKey.between(lower, upper);
        }
        String projectSortKey = allocated.orElseThrow(() -> ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ORDER_DENSE"));
        WorkItem candidate;
        try {
            candidate = moving.reorderProject(projectSortKey, actor.userId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "PROJECT_ORDER_MOVE_REJECTED");
        }
        WorkItem after = workItems.reorderProject(candidate, expectedVersion)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true,
                labels.statuses(project.companyId(), project.projectId())));
    }

    private boolean matchesProjectOrderScope(UUID companyId, UUID parentWorkItemId,
            UUID workItemId) {
        return parentWorkItemId == null
                ? !relations.hasActiveParent(companyId, workItemId)
                : relations.isActiveChildOf(companyId, parentWorkItemId, workItemId);
    }

    private void rebalanceProjectOrder(UUID companyId, UUID projectId, UUID movingId,
            String pivotKey) {
        List<RankedProjectWorkItem> window = workItems.findProjectRankWindow(
                companyId, projectId, pivotKey, movingId, 100);
        if (window.isEmpty()) return;
        String outsideLower = workItems.findProjectNeighborBefore(companyId, projectId,
                        window.getFirst().rank(), movingId)
                .filter(item -> window.stream().noneMatch(candidate -> candidate.id().equals(item.id())))
                .map(RankedProjectWorkItem::rank).orElse(null);
        String outsideUpper = workItems.findProjectNeighborAfter(companyId, projectId,
                        window.getLast().rank(), movingId)
                .filter(item -> window.stream().noneMatch(candidate -> candidate.id().equals(item.id())))
                .map(RankedProjectWorkItem::rank).orElse(null);
        Map<UUID, String> replacements = new LinkedHashMap<>();
        for (int index = 0; index < window.size(); index++) {
            String rank = ProjectSortKey.evenlySpacedBetween(outsideLower, outsideUpper,
                            index + 1, window.size())
                    .orElseThrow(() -> ApplicationException.withReason(
                            StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ORDER_DENSE"));
            replacements.put(window.get(index).id(), rank);
        }
        workItems.rewriteProjectSortKeys(companyId, projectId, replacements);
    }

    private StoredCommandResult inlineUpdate(InlineUpdate command, WorkItemLocator locator) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(),
                        locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireActiveContent(content);
        WorkItem before = workItems.lock(project.companyId(), project.projectId(), content.id(),
                        command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        if ("ASSIGNEE".equals(command.field()))
            requireActiveAssignee(project, command.assigneeUserId());
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
                    "ASSIGNEE".equals(command.field()) ? command.assigneeUserId() : before.assigneeUserId(),
                    before.description(), before.notes(), before.timelineStartDate(),
                    before.timelineEndDate(),
                    "DUE_DATE".equals(command.field()) ? command.dueDate() : before.dueDate(),
                    command.actor().userId(), clock.instant());
        } catch (IllegalArgumentException exception) {
            throw validation("body", "INVALID_WORK_ITEM", exception.getMessage());
        }
        List<String> changedFields = changed(before, candidate);
        if (changedFields.isEmpty())
            return stored(200, detail(before, people(project.companyId(), List.of(before)), true,
                    statusLabels));
        WorkItem after = workItems.update(candidate, command.expectedVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        appendFieldsChanged(after, command.actor(), changedFields);
        appendAssignmentChange(before, after, command.actor());
        return stored(200, detail(after, people(project.companyId(), List.of(after)), true,
                statusLabels));
    }

    private static ApplicationException invalidProjectOrderAnchor() {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                "PROJECT_ORDER_ANCHOR_CONFLICT");
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

    private Map<UUID, MinimalUserSnapshot> people(UUID companyId, List<WorkItem> rows) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (WorkItem item : rows) {
            userIds.add(item.reporterUserId());
            if (item.assigneeUserId() != null) userIds.add(item.assigneeUserId());
        }
        return users.findByUserIds(companyId, userIds);
    }

    private WorkItemSortRanks sortRanks(UUID companyId, UUID projectId, UUID contentId,
            WorkItemQuery query, List<WorkItemLabelModels.StatusLabel> statusLabels,
            List<WorkItemLabelModels.PriorityLabel> priorityLabels) {
        Map<String, Integer> statusRanks = new LinkedHashMap<>();
        statusLabels.stream().sorted(Comparator.comparingInt(WorkItemLabelModels.StatusLabel::sortOrder))
                .forEach(status -> statusRanks.put(status.code(), statusRanks.size()));
        Map<String, Integer> priorityRanks = new LinkedHashMap<>();
        priorityLabels.stream().sorted(Comparator.comparingInt(WorkItemLabelModels.PriorityLabel::sortOrder))
                .forEach(priority -> priorityRanks.put(priority.code(), priorityRanks.size()));
        boolean assigneeSort = query.sorts().stream()
                .anyMatch(sort -> sort.field() == ContentViewConfig.SortField.ASSIGNEE);
        boolean reporterSort = query.sorts().stream()
                .anyMatch(sort -> sort.field() == ContentViewConfig.SortField.REPORTER);
        if (!assigneeSort && !reporterSort)
            return new WorkItemSortRanks(statusRanks, priorityRanks, Map.of(), Map.of());
        Set<UUID> participantIds = contentId == null
                ? workItems.findProjectParticipantUserIds(companyId, projectId)
                : workItems.findParticipantUserIds(companyId, projectId, contentId);
        Map<UUID, MinimalUserSnapshot> snapshots = users.findByUserIds(companyId, participantIds);
        List<MinimalUserSnapshot> ordered = snapshots.values().stream()
                .sorted(Comparator.comparing(MinimalUserSnapshot::displayName,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MinimalUserSnapshot::displayName)
                        .thenComparing(snapshot -> snapshot.userId().toString()))
                .toList();
        Map<UUID, Integer> personRanks = new LinkedHashMap<>();
        ordered.forEach(snapshot -> personRanks.put(snapshot.userId(), personRanks.size()));
        return new WorkItemSortRanks(statusRanks, priorityRanks,
                assigneeSort ? personRanks : Map.of(), reporterSort ? personRanks : Map.of());
    }

    private static WorkItemSummary summary(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people, boolean canEditFields,
            List<WorkItemLabelModels.StatusLabel> statusLabels) {
        return new WorkItemSummary(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.type().name(), item.title(), item.statusCode(), item.statusCategory().name(),
                priorityName(item), item.assigneeUserId(), assigneeDisplayName(item, people),
                item.reporterUserId(), displayName(people.get(item.reporterUserId())),
                item.description(), item.notes(), item.timelineStartDate(), item.timelineEndDate(),
                item.dueDate(), item.rowVersion(), StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(canEditFields, canEditFields, canEditFields,
                        canEditFields, canEditFields, false,
                        availableTransitions(item, canEditFields, statusLabels)), item.updatedAt());
    }

    private static ProjectWorkItemListItem projectListItem(WorkItem item, Content content,
            Map<UUID, MinimalUserSnapshot> people, boolean canEditFields,
            List<WorkItemLabelModels.StatusLabel> statusLabels, long subitemCount) {
        return new ProjectWorkItemListItem(item.id(), item.projectId(), item.contentId(),
                content == null ? "未知 Content" : content.name(), item.itemNo(), item.type().name(),
                item.title(), item.statusCode(), item.statusCategory().name(), priorityName(item),
                item.assigneeUserId(), assigneeDisplayName(item, people), item.dueDate(),
                item.rowVersion(), StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(canEditFields, canEditFields, canEditFields,
                        canEditFields, canEditFields, false,
                        availableTransitions(item, canEditFields, statusLabels)), subitemCount,
                item.updatedAt());
    }

    private static WorkItemDetail detail(WorkItem item,
            Map<UUID, MinimalUserSnapshot> people, boolean canEditFields,
            List<WorkItemLabelModels.StatusLabel> statusLabels) {
        return new WorkItemDetail(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.type().name(), item.title(), item.statusCode(), item.statusCategory().name(),
                priorityName(item), item.assigneeUserId(), assigneeDisplayName(item, people),
                item.reporterUserId(), displayName(people.get(item.reporterUserId())),
                item.description(), item.notes(), item.timelineStartDate(), item.timelineEndDate(),
                item.dueDate(), item.rowVersion(), StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(canEditFields && !item.deleted(),
                        canEditFields && !item.deleted(), canEditFields && !item.deleted(),
                        canEditFields && !item.deleted(), canEditFields && !item.deleted(),
                        canEditFields && item.deleted(),
                        availableTransitions(item, canEditFields && !item.deleted(), statusLabels)),
                item.createdAt(), item.updatedAt(), item.deleted(), item.deletedAt(),
                item.deletedByUserId(), item.deleteReason());
    }

    private static List<WorkItemTransitionOption> availableTransitions(WorkItem item,
            boolean canTransition, List<WorkItemLabelModels.StatusLabel> statusLabels) {
        if (!canTransition) return List.of();
        return statusLabels.stream().filter(WorkItemLabelModels.StatusLabel::active)
                .filter(status -> !status.code().equals(item.statusCode()))
                .sorted(Comparator.comparingInt(WorkItemLabelModels.StatusLabel::sortOrder))
                .map(status -> new WorkItemTransitionOption(status.code(), status.displayName(),
                        status.statusCategory(), false))
                .toList();
    }

    private static String projectCursorFingerprint(UUID projectId, ContentViewType view,
            WorkItemQuery query) {
        String statuses = query.statuses().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        String priorities = query.priorities().stream().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String assignees = query.assigneeUserIds().stream().map(UUID::toString).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String contents = query.contentIds().stream().map(UUID::toString).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String sorts = query.sorts().stream().map(sort -> sort.field().name() + ","
                        + sort.direction().name()).collect(java.util.stream.Collectors.joining(";"));
        String canonical = String.join("\n", projectId.toString(), view.name(),
                Objects.toString(query.query(), ""), statuses, priorities, assignees, contents,
                Objects.toString(query.dueFrom(), ""), Objects.toString(query.dueTo(), ""),
                Objects.toString(query.updatedAfter(), ""), sorts);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private RankAllocation allocateRank(UUID companyId, UUID projectId, UUID contentId,
            String statusCode, WorkItemRankPlacement placement, UUID anchorWorkItemId,
            UUID movingWorkItemId) {
        List<RankedWorkItem> lane = workItems.findRankOrder(companyId, projectId, contentId, statusCode);
        List<UUID> original = lane.stream().map(RankedWorkItem::id).toList();
        List<RankedWorkItem> available = lane.stream()
                .filter(item -> !item.id().equals(movingWorkItemId)).toList();
        int targetIndex = targetIndex(available, placement, anchorWorkItemId, movingWorkItemId);
        if (movingWorkItemId != null && original.contains(movingWorkItemId)) {
            List<UUID> desired = new ArrayList<>(available.stream().map(RankedWorkItem::id).toList());
            desired.add(targetIndex, movingWorkItemId);
            if (desired.equals(original)) {
                String current = lane.stream().filter(item -> item.id().equals(movingWorkItemId))
                        .findFirst().orElseThrow().rank();
                return new RankAllocation(current, true);
            }
        }
        Optional<String> candidate = KanbanRank.between(
                targetIndex == 0 ? null : available.get(targetIndex - 1).rank(),
                targetIndex == available.size() ? null : available.get(targetIndex).rank());
        if (candidate.isEmpty()) {
            Map<UUID, String> rebalanced = new LinkedHashMap<>();
            for (int index = 0; index < lane.size(); index++)
                rebalanced.put(lane.get(index).id(), KanbanRank.evenlySpaced(index + 1, lane.size()));
            workItems.rewriteRanks(companyId, projectId, contentId, statusCode, rebalanced);
            lane = workItems.findRankOrder(companyId, projectId, contentId, statusCode);
            available = lane.stream().filter(item -> !item.id().equals(movingWorkItemId)).toList();
            targetIndex = targetIndex(available, placement, anchorWorkItemId, movingWorkItemId);
            candidate = KanbanRank.between(
                    targetIndex == 0 ? null : available.get(targetIndex - 1).rank(),
                    targetIndex == available.size() ? null : available.get(targetIndex).rank());
        }
        return new RankAllocation(candidate.orElseThrow(
                () -> new IllegalStateException("Kanban rank rebalance left no position")), false);
    }

    private static int targetIndex(List<RankedWorkItem> available,
            WorkItemRankPlacement placement, UUID anchorWorkItemId, UUID movingWorkItemId) {
        if (placement == WorkItemRankPlacement.START) return 0;
        if (placement == WorkItemRankPlacement.END) return available.size();
        if (Objects.equals(anchorWorkItemId, movingWorkItemId))
            throw invalidRankAnchor();
        for (int index = 0; index < available.size(); index++) {
            if (available.get(index).id().equals(anchorWorkItemId))
                return placement == WorkItemRankPlacement.BEFORE ? index : index + 1;
        }
        throw invalidRankAnchor();
    }

    private static WorkItemRankPlacement placement(String value, UUID anchorWorkItemId) {
        WorkItemRankPlacement placement;
        try {
            placement = WorkItemRankPlacement.valueOf(value);
        } catch (RuntimeException exception) {
            throw validation("placement", "INVALID_VALUE", "Kanban 定位方式无效");
        }
        boolean relative = placement == WorkItemRankPlacement.BEFORE
                || placement == WorkItemRankPlacement.AFTER;
        if (relative != (anchorWorkItemId != null))
            throw validation("anchorWorkItemId", "INVALID_COMBINATION",
                    "BEFORE/AFTER 必须且只能携带锚点工作项");
        return placement;
    }

    private static WorkItemLabelModels.StatusLabel status(
            List<WorkItemLabelModels.StatusLabel> statuses, String statusCode) {
        return statuses.stream().filter(value -> value.code().equals(statusCode))
                .findFirst().orElseThrow(() -> ApplicationException.withReason(
                        StandardErrorCode.INVALID_STATE_TRANSITION,
                        "WORK_ITEM_TARGET_STATUS_MISSING"));
    }

    private static WorkItemLabelModels.StatusLabel selectableStatus(
            List<WorkItemLabelModels.StatusLabel> statuses, String statusCode) {
        WorkItemLabelModels.StatusLabel status = status(statuses, statusCode);
        if (!status.active()) throw ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "WORK_ITEM_TARGET_STATUS_INACTIVE");
        return status;
    }

    private static void requireDistinctStatus(String fromStatus, String toStatus) {
        if (Objects.equals(fromStatus, toStatus)) throw ApplicationException.withReason(
                StandardErrorCode.INVALID_STATE_TRANSITION, "WORK_ITEM_TRANSITION_NOT_ALLOWED");
    }

    private static ApplicationException invalidRankAnchor() {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                "WORK_ITEM_RANK_ANCHOR_INVALID");
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

    private void appendRelationCreated(ParentChildRelation relation, CurrentActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("relationId", relation.id());
        payload.put("relationType", "PARENT_CHILD");
        payload.put("leftWorkItemId", relation.parentWorkItemId());
        payload.put("rightWorkItemId", relation.childWorkItemId());
        payload.put("leftProjectId", relation.projectId());
        payload.put("rightProjectId", relation.projectId());
        events.append(new EventDraft(RELATION_CREATED, 1, "WorkItemRelation", relation.id(), 0,
                relation.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
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

    private void appendRankChanged(WorkItem item, CurrentActor actor,
            WorkItemRankPlacement placement, UUID anchorWorkItemId) {
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

    private void appendDeleted(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = lifecycleEventPayload(item);
        payload.put("deletedAt", item.deletedAt());
        payload.put("deletedByUserId", item.deletedByUserId());
        payload.put("deleteReason", item.deleteReason());
        append(DELETED, item, actor, payload);
    }

    private void appendRestored(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = lifecycleEventPayload(item);
        payload.put("restoredAt", item.updatedAt());
        payload.put("restoredByUserId", actor.userId());
        append(RESTORED, item, actor, payload);
    }

    private static Map<String, Object> lifecycleEventPayload(WorkItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", item.id());
        payload.put("projectId", item.projectId());
        payload.put("contentId", item.contentId());
        payload.put("itemNo", item.itemNo());
        payload.put("title", item.title());
        payload.put("workItemType", item.type().name());
        payload.put("statusCode", item.statusCode());
        payload.put("statusCategory", item.statusCategory().name());
        payload.put("priority", priorityName(item));
        payload.put("rowVersion", item.rowVersion());
        return payload;
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
        payload.put("priority", priorityName(item));
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
        if (!Objects.equals(before.priority(), after.priority())) fields.add("priority");
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
                && content != null && content.status() == ContentStatus.ACTIVE
                && project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }

    private static String priority(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (!normalized.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw validation("priority", "INVALID_VALUE", "优先级无效");
        }
        return normalized;
    }

    private static String priorityName(WorkItem item) {
        return item.priority();
    }

    private static void requireAllowedPriorities(WorkItemQuery query,
            List<WorkItemLabelModels.PriorityLabel> priorities) {
        Set<String> allowed = priorities.stream().map(WorkItemLabelModels.PriorityLabel::code)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(query.priorities()))
            throw validation("priority", "INVALID_VALUE", "优先级筛选值无效");
    }

    private static void requireSelectablePriority(String requested,
            List<WorkItemLabelModels.PriorityLabel> priorities, String current) {
        if (requested == null) return;
        if (Objects.equals(requested, current)) return;
        boolean selectable = priorities.stream().anyMatch(label -> label.code().equals(requested)
                && label.active());
        if (!selectable)
            throw validation("priority", "INVALID_VALUE", "优先级不存在或已停用");
    }

    private static ContentViewType view(String value) {
        if (value == null || value.isBlank()) return ContentViewType.TABLE;
        try {
            return ContentViewType.valueOf(value);
        } catch (RuntimeException exception) {
            throw validation("view", "INVALID_VALUE", "工作项视图类型无效");
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

    private static String normalizeDeleteReason(String value) {
        if (value == null) throw validation("reason", "REQUIRED", "删除理由不能为空");
        String normalized = value.strip();
        if (normalized.isEmpty())
            throw validation("reason", "REQUIRED", "删除理由不能为空");
        if (normalized.length() > 500)
            throw validation("reason", "INVALID_LENGTH", "删除理由不能超过 500 字符");
        return normalized;
    }

    private static ApplicationException invalidLifecycle(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
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

    private record RankAllocation(String rank, boolean noOp) {}
    private record VisibleContent(ProjectAccessSnapshot project, Content content) {}
}
