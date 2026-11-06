package com.yumpoo.platform.workitem.application;

import static com.yumpoo.platform.workitem.application.WorkItemEvents.dueTimeText;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.priorityName;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemRules.canEdit;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireActor;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireAllowedPriorities;
import static com.yumpoo.platform.workitem.application.WorkItemRules.validation;
import static com.yumpoo.platform.workitem.application.WorkItemRules.view;
import static com.yumpoo.platform.workitem.application.WorkItemViews.assigneeDisplayName;
import static com.yumpoo.platform.workitem.application.WorkItemViews.assignees;
import static com.yumpoo.platform.workitem.application.WorkItemViews.availableTransitions;
import static com.yumpoo.platform.workitem.application.WorkItemViews.displayName;
import static com.yumpoo.platform.workitem.application.WorkItemViews.updatedByDisplayName;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery.ConnectTargetProjectSnapshot;
import com.yumpoo.platform.foundation.api.pagination.CursorPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.application.WorkItemViews.VisibleContent;
import com.yumpoo.platform.workitem.application.content.ContentRepository;
import com.yumpoo.platform.workitem.application.discussion.WorkItemUpdateRepository;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelModels;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelRepository;
import com.yumpoo.platform.workitem.application.time.TimeTrackingModels;
import com.yumpoo.platform.workitem.application.time.TimeTrackingRepository;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemViewType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkItemQueryService {
    private final WorkItemRepository workItems;
    private final WorkItemRelationRepository relations;
    private final WorkItemUpdateRepository updates;
    private final ContentRepository contents;
    private final WorkItemLabelRepository labels;
    private final MinimalUserSnapshotQuery users;
    private final Clock clock;
    private final TimeTrackingRepository timeTracking;
    private final ProjectConnectionTargetQuery connectionTargets;
    private final ProjectWorkItemCursorCodec projectCursors = new ProjectWorkItemCursorCodec();
    private final ProjectWorkItemFilterCursorCodec projectFilterCursors = new ProjectWorkItemFilterCursorCodec();
    private final WorkItemViews views;

    public WorkItemQueryService(
            WorkItemRepository workItems,
            WorkItemRelationRepository relations,
            WorkItemUpdateRepository updates,
            ContentRepository contents,
            WorkItemLabelRepository labels,
            MinimalUserSnapshotQuery users,
            Clock clock,
            TimeTrackingRepository timeTracking,
            ProjectConnectionTargetQuery connectionTargets,
            WorkItemViews views) {
        this.workItems = workItems;
        this.relations = relations;
        this.updates = updates;
        this.contents = contents;
        this.labels = labels;
        this.users = users;
        this.clock = clock;
        this.timeTracking = timeTracking;
        this.connectionTargets = connectionTargets;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public WorkItemPage list(
            CurrentActor actor, UUID contentId, WorkItemQuery.Request request, String view, OffsetPageRequest page) {
        VisibleContent visible = views.visibleContent(actor, contentId);
        List<WorkItemLabelModels.StatusLabel> statusLabels =
                labels.statuses(visible.project().companyId(), visible.project().projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels = labels.priorities(
                visible.project().companyId(), visible.project().projectId());
        Set<String> allowedStatuses = statusLabels.stream()
                .map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        WorkItemViewType effectiveView = view(view);
        if (effectiveView == WorkItemViewType.KANBAN) {
            if (query.statuses().size() != 1)
                throw validation("status", "KANBAN_REQUIRES_ONE_STATUS", "Kanban 泳道查询必须且只能指定一个状态");
            if (request.sorts() != null && !request.sorts().isEmpty())
                throw validation("sort", "KANBAN_SORT_FIXED", "Kanban 固定按 rank 排序");
        }
        WorkItemSortRanks ranks = sortRanks(
                visible.project().companyId(),
                visible.project().projectId(),
                contentId,
                query,
                statusLabels,
                priorityLabels);
        List<WorkItem> rows = workItems.findPage(
                visible.project().companyId(),
                visible.project().projectId(),
                contentId,
                query,
                ranks,
                effectiveView,
                page);
        long total = workItems.countPage(
                visible.project().companyId(), visible.project().projectId(), contentId, query);
        Map<UUID, MinimalUserSnapshot> people = views.people(visible.project().companyId(), rows);
        OffsetPageResponse<WorkItemSummary> response = OffsetPageResponse.of(
                rows.stream()
                        .map(item -> summary(item, people, canEdit(visible.project(), visible.content()), statusLabels))
                        .toList(),
                page,
                total);
        return new WorkItemPage(
                response.items(), response.page(), response.size(), response.totalElements(), response.totalPages());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProjectWorkItemCursorPage listProject(
            CurrentActor actor, UUID projectId, WorkItemQuery.Request request, String view, CursorPageRequest page) {
        requireActor(actor);
        ProjectAccessSnapshot project = views.visible(actor, projectId);
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels =
                labels.priorities(project.companyId(), project.projectId());
        Set<String> allowedStatuses = statusLabels.stream()
                .map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        WorkItemViewType effectiveView = view(view);
        if (effectiveView == WorkItemViewType.KANBAN) {
            if (query.statuses().size() != 1)
                throw validation("status", "KANBAN_REQUIRES_ONE_STATUS", "项目 Kanban 泳道查询必须且只能指定一个状态");
            if (request.sorts() != null && !request.sorts().isEmpty())
                throw validation("sort", "KANBAN_SORT_FIXED", "项目 Kanban 固定按更新时间倒序");
        }
        WorkItemSortRanks ranks =
                sortRanks(project.companyId(), project.projectId(), null, query, statusLabels, priorityLabels);
        String fingerprint = projectCursorFingerprint(projectId, effectiveView, query);
        ProjectWorkItemCursorCodec.Cursor decoded = projectCursors.decode(page.cursor());
        if (decoded != null && (!decoded.fingerprint().equals(fingerprint) || decoded.view() != effectiveView))
            throw validation("cursor", "CURSOR_QUERY_MISMATCH", "游标不属于当前项目查询");
        Instant timeAsOf = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        long timeRevision = timeTracking.revision(actor.companyId(), projectId);
        if (query.usesTimeTracking()) {
            if (decoded != null && (decoded.timeAsOf() == null || decoded.timeRevision() != timeRevision))
                throw validation("cursor", "TIME_TRACKING_CURSOR_EXPIRED", "计时记录已变化，请刷新列表");
            if (decoded != null) timeAsOf = decoded.timeAsOf();
            var filter = query.timeTracking();
            query = query.withTime(new WorkItemQuery.TimeFilter(
                    filter == null ? null : filter.state(),
                    filter == null ? null : filter.minMs(),
                    filter == null ? null : filter.maxMs(),
                    timeAsOf,
                    timeRevision,
                    decoded == null ? 0 : decoded.timeDurationMs()));
        }
        if (query.scope() != null) query = query.withScope(query.scope().at(timeAsOf));
        WorkItemRepository.ProjectCursorAnchor anchor = decoded == null ? null : decoded.anchor();
        List<WorkItem> rows = new ArrayList<>(workItems.findProjectCursorPage(
                project.companyId(), project.projectId(), query, ranks, effectiveView, anchor, page.limit() + 1));
        boolean hasMore = rows.size() > page.limit();
        if (hasMore) rows = new ArrayList<>(rows.subList(0, page.limit()));
        List<ProjectWorkItemListItem> items = tableItems(actor, project, rows, timeAsOf);
        if (effectiveView == WorkItemViewType.TABLE) {
            Map<UUID, Long> matched = workItems.countMatchingChildren(
                    project.companyId(),
                    project.projectId(),
                    query,
                    rows.stream().map(WorkItem::id).toList());
            items = items.stream()
                    .map(item -> item.withMatchedSubitemCount(matched.getOrDefault(item.id(), 0L)))
                    .toList();
        }
        String nextCursor = hasMore && !rows.isEmpty()
                ? projectCursors.encode(new ProjectWorkItemCursorCodec.Cursor(
                        fingerprint,
                        effectiveView,
                        WorkItemRepository.ProjectCursorAnchor.from(rows.getLast()),
                        query.usesTimeTracking() ? timeAsOf : null,
                        timeRevision,
                        items.getLast().timeTracking().rollupDurationMs()))
                : null;
        return new ProjectWorkItemCursorPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public WorkItemSubitemList listSubitems(CurrentActor actor, UUID parentWorkItemId, WorkItemQuery.Request request) {
        requireActor(actor);
        WorkItemLocator locator = workItems
                .findLocator(actor.companyId(), parentWorkItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(actor, locator.projectId());
        workItems
                .find(project.companyId(), project.projectId(), locator.contentId(), parentWorkItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels =
                labels.priorities(project.companyId(), project.projectId());
        Set<String> allowedStatuses = statusLabels.stream()
                .map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        WorkItemSortRanks ranks =
                sortRanks(project.companyId(), project.projectId(), null, query, statusLabels, priorityLabels);
        List<WorkItem> rows =
                workItems.findSubitems(project.companyId(), project.projectId(), parentWorkItemId, query, ranks);
        Map<UUID, Content> contentById = new LinkedHashMap<>();
        contents.findAll(project.companyId(), project.projectId())
                .forEach(content -> contentById.put(content.id(), content));
        Map<UUID, MinimalUserSnapshot> people = views.people(project.companyId(), rows);
        Map<UUID, Long> discussionCounts = updates.countActiveDiscussions(
                project.companyId(), rows.stream().map(WorkItem::id).toList());
        Map<UUID, Long> subitemCounts = relations.countActiveChildren(
                project.companyId(), rows.stream().map(WorkItem::id).toList());
        Map<UUID, TimeTrackingModels.TimeTrackingSummary> timeSummaries = timeTracking
                .summaries(
                        actor.companyId(),
                        project.projectId(),
                        actor.userId(),
                        rows.stream().map(WorkItem::id).toList(),
                        clock.instant())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        TimeTrackingModels.TimeTrackingSummary::workItemId, java.util.function.Function.identity()));
        return new WorkItemSubitemList(rows.stream()
                .map(item -> projectListItem(
                                item,
                                contentById.get(item.contentId()),
                                people,
                                canEdit(project, contentById.get(item.contentId())),
                                statusLabels,
                                subitemCounts.getOrDefault(item.id(), 0L),
                                discussionCounts.getOrDefault(item.id(), 0L))
                        .withTime(timeSummaries.get(item.id())))
                .toList());
    }

    @Transactional(readOnly = true)
    public ProjectWorkItemFilterOptionCursorPage listProjectFilterOptions(
            CurrentActor actor,
            UUID projectId,
            String field,
            boolean countSubitems,
            WorkItemQuery.Request request,
            CursorPageRequest page) {
        requireActor(actor);
        ProjectAccessSnapshot project = views.visible(actor, projectId);
        List<WorkItemLabelModels.StatusLabel> statusLabels = labels.statuses(project.companyId(), project.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorityLabels =
                labels.priorities(project.companyId(), project.projectId());
        Set<String> allowedStatuses = statusLabels.stream()
                .map(WorkItemLabelModels.StatusLabel::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        WorkItemQuery query = WorkItemQuery.parse(request, allowedStatuses);
        requireAllowedPriorities(query, priorityLabels);
        String normalizedField;
        try {
            normalizedField = Objects.requireNonNull(field).strip().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of(
                            "TITLE",
                            "ASSIGNEE",
                            "ASSIGNEE_SET",
                            "STATUS",
                            "PRIORITY",
                            "CONTENT",
                            "DUE_DATE",
                            "UPDATED_AT",
                            "INCOMING_PROJECT")
                    .contains(normalizedField)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw validation("field", "INVALID_VALUE", "筛选选项字段不受支持");
        }
        String fingerprint = projectCursorFingerprint(projectId, WorkItemViewType.TABLE, query) + ":" + normalizedField
                + (countSubitems ? ":items" : "");
        ProjectWorkItemFilterCursorCodec.Cursor decoded = projectFilterCursors.decode(page.cursor());
        if (decoded != null
                && (!decoded.fingerprint().equals(fingerprint)
                        || !decoded.field().equals(normalizedField)))
            throw validation("cursor", "CURSOR_QUERY_MISMATCH", "筛选选项游标不属于当前查询");
        List<WorkItemRepository.FilterOptionCount> rows = new ArrayList<>(workItems.findProjectFilterOptions(
                project.companyId(),
                project.projectId(),
                query,
                normalizedField,
                countSubitems,
                decoded == null ? null : decoded.lastValue(),
                page.limit() + 1));
        boolean hasMore = rows.size() > page.limit();
        if (hasMore) rows = new ArrayList<>(rows.subList(0, page.limit()));
        Map<UUID, String> contentNames = contents.findAll(project.companyId(), project.projectId()).stream()
                .collect(java.util.stream.Collectors.toMap(Content::id, Content::name));
        Set<UUID> userIds = rows.stream()
                .filter(row -> Set.of("ASSIGNEE", "ASSIGNEE_SET").contains(normalizedField))
                .map(WorkItemRepository.FilterOptionCount::value)
                .filter(value -> !"__NULL__".equals(value))
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(UUID::fromString)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, MinimalUserSnapshot> people = users.findByUserIds(project.companyId(), userIds);
        Map<String, String> statusNames = statusLabels.stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkItemLabelModels.StatusLabel::code, WorkItemLabelModels.StatusLabel::displayName));
        Map<String, String> priorityNames = priorityLabels.stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkItemLabelModels.PriorityLabel::code, WorkItemLabelModels.PriorityLabel::displayName));
        Map<UUID, ConnectTargetProjectSnapshot> incomingProjects = "INCOMING_PROJECT".equals(normalizedField)
                ? connectionTargets.findByIds(
                        project.companyId(),
                        rows.stream().map(row -> UUID.fromString(row.value())).toList())
                : Map.of();
        List<ProjectWorkItemFilterOption> items = rows.stream()
                .map(row -> {
                    String label = switch (normalizedField) {
                        case "CONTENT" -> contentNames.getOrDefault(UUID.fromString(row.value()), row.value());
                        case "ASSIGNEE" ->
                            "__NULL__".equals(row.value())
                                    ? "未分配"
                                    : Optional.ofNullable(people.get(UUID.fromString(row.value())))
                                            .map(MinimalUserSnapshot::displayName)
                                            .orElse("未知成员");
                        case "ASSIGNEE_SET" ->
                            "__NULL__".equals(row.value())
                                    ? "未分配"
                                    : java.util.Arrays.stream(row.value().split(","))
                                            .map(UUID::fromString)
                                            .map(id -> Map.entry(
                                                    id.toString(),
                                                    Optional.ofNullable(people.get(id))
                                                            .map(MinimalUserSnapshot::displayName)
                                                            .orElse("未知成员")))
                                            .sorted(java.util.Comparator.<Map.Entry<String, String>, String>comparing(
                                                            Map.Entry::getValue, String.CASE_INSENSITIVE_ORDER)
                                                    .thenComparing(Map.Entry::getValue)
                                                    .thenComparing(Map.Entry::getKey))
                                            .map(Map.Entry::getValue)
                                            .collect(java.util.stream.Collectors.joining("、"));
                        case "STATUS" -> statusNames.getOrDefault(row.value(), row.value());
                        case "PRIORITY" ->
                            "__NULL__".equals(row.value())
                                    ? "未设置"
                                    : priorityNames.getOrDefault(row.value(), row.value());
                        case "DUE_DATE" -> "__NULL__".equals(row.value()) ? "未设置" : row.value();
                        case "INCOMING_PROJECT" ->
                            Optional.ofNullable(incomingProjects.get(UUID.fromString(row.value())))
                                    .map(ConnectTargetProjectSnapshot::name)
                                    .orElse("项目");
                        default -> row.value();
                    };
                    return new ProjectWorkItemFilterOption(row.value(), label, row.count());
                })
                .toList();
        String nextCursor = hasMore && !rows.isEmpty()
                ? projectFilterCursors.encode(new ProjectWorkItemFilterCursorCodec.Cursor(
                        fingerprint, normalizedField, rows.getLast().value()))
                : null;
        return new ProjectWorkItemFilterOptionCursorPage(items, nextCursor);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ArchivedWorkItemPage listArchived(CurrentActor actor, UUID projectId, String query, OffsetPageRequest page) {
        requireActor(actor);
        ProjectAccessSnapshot project = views.visible(actor, projectId);
        String q = query == null || query.isBlank() ? null : query.strip();
        List<WorkItemRepository.ArchivedRow> rows =
                workItems.findArchived(project.companyId(), projectId, q, page.page() * page.size(), page.size());
        long total = workItems.countArchived(project.companyId(), projectId, q);
        List<WorkItem> items =
                rows.stream().map(WorkItemRepository.ArchivedRow::item).toList();
        Map<UUID, MinimalUserSnapshot> people = views.people(project.companyId(), items);
        Map<UUID, Long> subitemCounts = relations.countActiveChildren(
                project.companyId(), items.stream().map(WorkItem::id).toList());
        return new ArchivedWorkItemPage(
                rows.stream()
                        .map(row -> new ArchivedWorkItem(
                                row.item().id(),
                                row.item().itemNo(),
                                row.item().title(),
                                row.item().statusCode(),
                                row.item().statusCategory().name(),
                                row.parentTitle(),
                                subitemCounts.getOrDefault(row.item().id(), 0L),
                                row.item().updatedAt(),
                                updatedByDisplayName(row.item(), people),
                                StrongEtag.format(row.item().rowVersion())))
                        .toList(),
                page.page(),
                page.size(),
                total,
                (int) ((total + page.size() - 1) / page.size()));
    }

    @Transactional(readOnly = true)
    public WorkItemDetail find(CurrentActor actor, UUID workItemId) {
        requireActor(actor);
        WorkItemLocator locator = workItems
                .findLocator(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems
                .find(project.companyId(), project.projectId(), locator.contentId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return views.detail(
                item,
                views.people(project.companyId(), List.of(item)),
                canEdit(project, content),
                labels.statuses(project.companyId(), project.projectId()));
    }

    @Transactional(readOnly = true)
    public WorkItemDetail findForLifecycle(CurrentActor actor, UUID workItemId) {
        requireActor(actor);
        WorkItemLocator locator = workItems
                .findLocatorIncludingDeleted(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(actor, locator.projectId());
        Content content = contents.find(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItem item = workItems
                .findIncludingDeleted(project.companyId(), project.projectId(), locator.contentId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return views.detail(
                item,
                views.people(project.companyId(), List.of(item)),
                canEdit(project, content),
                labels.statuses(project.companyId(), project.projectId()));
    }

    private WorkItemSortRanks sortRanks(
            UUID companyId,
            UUID projectId,
            UUID contentId,
            WorkItemQuery query,
            List<WorkItemLabelModels.StatusLabel> statusLabels,
            List<WorkItemLabelModels.PriorityLabel> priorityLabels) {
        Map<String, Integer> statusRanks = new LinkedHashMap<>();
        statusLabels.stream()
                .sorted(Comparator.comparingInt(WorkItemLabelModels.StatusLabel::sortOrder))
                .forEach(status -> statusRanks.put(status.code(), statusRanks.size()));
        Map<String, Integer> priorityRanks = new LinkedHashMap<>();
        priorityLabels.stream()
                .sorted(Comparator.comparingInt(WorkItemLabelModels.PriorityLabel::sortOrder))
                .forEach(priority -> priorityRanks.put(priority.code(), priorityRanks.size()));
        boolean assigneeSort = query.sorts().stream().anyMatch(sort -> sort.field() == WorkItemSortField.ASSIGNEE);
        boolean reporterSort = query.sorts().stream().anyMatch(sort -> sort.field() == WorkItemSortField.REPORTER);
        Map<UUID, Integer> contentRanks = new LinkedHashMap<>();
        contents.findAll(companyId, projectId).stream()
                .sorted(Comparator.comparingInt(Content::sortOrder).thenComparing(Content::id))
                .forEach(content -> contentRanks.put(content.id(), contentRanks.size()));
        if (!assigneeSort && !reporterSort)
            return new WorkItemSortRanks(statusRanks, priorityRanks, contentRanks, Map.of(), Map.of());
        Set<UUID> participantIds = contentId == null
                ? workItems.findProjectParticipantUserIds(companyId, projectId)
                : workItems.findParticipantUserIds(companyId, projectId, contentId);
        Map<UUID, MinimalUserSnapshot> snapshots = users.findByUserIds(companyId, participantIds);
        List<MinimalUserSnapshot> ordered = snapshots.values().stream()
                .sorted(Comparator.comparing(MinimalUserSnapshot::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MinimalUserSnapshot::displayName)
                        .thenComparing(snapshot -> snapshot.userId().toString()))
                .toList();
        Map<UUID, Integer> personRanks = new LinkedHashMap<>();
        ordered.forEach(snapshot -> personRanks.put(snapshot.userId(), personRanks.size()));
        return new WorkItemSortRanks(
                statusRanks,
                priorityRanks,
                contentRanks,
                assigneeSort ? personRanks : Map.of(),
                reporterSort ? personRanks : Map.of());
    }

    private WorkItemSummary summary(
            WorkItem item,
            Map<UUID, MinimalUserSnapshot> people,
            boolean canEditFields,
            List<WorkItemLabelModels.StatusLabel> statusLabels) {
        Content content = contents.find(item.companyId(), item.projectId(), item.contentId())
                .orElse(null);
        return new WorkItemSummary(
                item.id(),
                item.projectId(),
                item.contentId(),
                item.itemNo(),
                content == null ? "未知类别" : content.name(),
                content == null ? "GRAY" : content.colorToken(),
                item.title(),
                item.statusCode(),
                item.statusCategory().name(),
                priorityName(item),
                item.assigneeUserId(),
                assigneeDisplayName(item, people),
                assignees(item, people),
                item.reporterUserId(),
                displayName(people.get(item.reporterUserId())),
                item.description(),
                item.notes(),
                item.timelineStartDate(),
                item.timelineEndDate(),
                item.dueDate(),
                dueTimeText(item),
                item.completedAt(),
                item.rowVersion(),
                StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(
                        canEditFields,
                        canEditFields,
                        canEditFields,
                        canEditFields,
                        canEditFields,
                        false,
                        availableTransitions(item, canEditFields, statusLabels)),
                item.updatedAt());
    }

    @Transactional(readOnly = true)
    public List<ProjectWorkItemListItem> tableItemsByIds(
            CurrentActor actor, UUID projectId, List<UUID> ids, Instant asOf) {
        requireActor(actor);
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 100) throw validation("ids", "TOO_MANY", "每次最多读取 100 个工作项");
        var project = views.visible(actor, projectId);
        return tableItems(actor, project, workItems.findByIds(actor.companyId(), projectId, ids), asOf);
    }

    private List<ProjectWorkItemListItem> tableItems(
            CurrentActor actor, ProjectAccessSnapshot project, List<WorkItem> rows, Instant timeAsOf) {
        UUID projectId = project.projectId();
        var statusLabels = labels.statuses(project.companyId(), projectId);
        Map<UUID, Content> contentById = new LinkedHashMap<>();
        contents.findAll(project.companyId(), project.projectId())
                .forEach(content -> contentById.put(content.id(), content));
        Map<UUID, MinimalUserSnapshot> people = views.people(project.companyId(), rows);
        Map<UUID, Long> discussionCounts = updates.countActiveDiscussions(
                project.companyId(), rows.stream().map(WorkItem::id).toList());
        Map<UUID, Long> subitemCounts = relations.countActiveChildren(
                project.companyId(), rows.stream().map(WorkItem::id).toList());
        Map<UUID, TimeTrackingModels.TimeTrackingSummary> timeSummaries = timeTracking
                .summaries(
                        actor.companyId(),
                        projectId,
                        actor.userId(),
                        rows.stream().map(WorkItem::id).toList(),
                        timeAsOf)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        TimeTrackingModels.TimeTrackingSummary::workItemId, java.util.function.Function.identity()));
        return rows.stream()
                .map(item -> projectListItem(
                                item,
                                contentById.get(item.contentId()),
                                people,
                                canEdit(project, contentById.get(item.contentId())) && !item.archived(),
                                statusLabels,
                                subitemCounts.getOrDefault(item.id(), 0L),
                                discussionCounts.getOrDefault(item.id(), 0L))
                        .withTime(timeSummaries.get(item.id())))
                .toList();
    }

    private static ProjectWorkItemListItem projectListItem(
            WorkItem item,
            Content content,
            Map<UUID, MinimalUserSnapshot> people,
            boolean canEditFields,
            List<WorkItemLabelModels.StatusLabel> statusLabels,
            long subitemCount,
            long discussionCount) {
        return new ProjectWorkItemListItem(
                item.id(),
                item.projectId(),
                item.contentId(),
                content == null ? "未知类别" : content.name(),
                content == null ? "GRAY" : content.colorToken(),
                item.itemNo(),
                item.title(),
                item.statusCode(),
                item.statusCategory().name(),
                priorityName(item),
                item.assigneeUserId(),
                assigneeDisplayName(item, people),
                assignees(item, people),
                item.dueDate(),
                dueTimeText(item),
                item.completedAt(),
                item.rowVersion(),
                StrongEtag.format(item.rowVersion()),
                new WorkItemCapabilities(
                        canEditFields,
                        canEditFields,
                        canEditFields,
                        canEditFields,
                        canEditFields,
                        false,
                        availableTransitions(item, canEditFields, statusLabels)),
                subitemCount,
                discussionCount,
                item.updatedAt(),
                item.updatedByUserId(),
                updatedByDisplayName(item, people));
    }

    private static String projectCursorFingerprint(UUID projectId, WorkItemViewType view, WorkItemQuery query) {
        String statuses = query.statuses().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        String priorities = query.priorities().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        String assignees = query.assigneeUserIds().stream()
                .map(UUID::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String contents = query.contentIds().stream()
                .map(UUID::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String sorts = query.sorts().stream()
                .map(sort -> sort.field().name() + "," + sort.direction().name())
                .collect(java.util.stream.Collectors.joining(";"));
        String canonical = String.join(
                "\n",
                projectId.toString(),
                view.name(),
                Objects.toString(query.query(), ""),
                statuses,
                priorities,
                assignees,
                contents,
                Objects.toString(query.dueFrom(), ""),
                Objects.toString(query.dueTo(), ""),
                Objects.toString(query.updatedAfter(), ""),
                sorts,
                query.timeTracking() == null
                        ? ""
                        : Objects.toString(query.timeTracking().state(), "") + ":"
                                + query.timeTracking().minMs() + ":"
                                + query.timeTracking().maxMs());
        if (!query.assigneeSetUserIds().isEmpty()) canonical += "\nassigneeSet=" + query.assigneeSetKey();
        if (query.emptyField() != null) canonical += "\nemptyField=" + query.emptyField();
        if (!query.group().equals(WorkItemQuery.GroupFilter.NONE))
            canonical += "\ngroup=" + query.group().fingerprint();
        if (query.scope() != null) canonical += "\nchart=" + query.scope().fingerprint();
        canonical += "\nconnections=" + query.connections().fingerprint();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
