package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemCommands.Create;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;

@Service
public class WorkItemService {
    private static final String CREATED = "workitem.work_item_created";

    private final WorkItemRepository workItems;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectTemplateVersionQuery templates;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkItemService(WorkItemRepository workItems, ContentRepository contents,
            ProjectAccessSnapshotQuery access, ProjectFactWriteGuard writeGuard,
            ProjectTemplateVersionQuery templates, MinimalUserSnapshotQuery users,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events,
            ObjectMapper objectMapper, Clock clock) {
        this.workItems = workItems; this.contents = contents; this.access = access;
        this.writeGuard = writeGuard; this.templates = templates; this.users = users;
        this.idempotency = idempotency; this.events = events;
        this.objectMapper = objectMapper; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkItemPage list(CurrentActor actor, UUID contentId, Collection<String> requestedStatuses,
            OffsetPageRequest page) {
        VisibleContent visible = visibleContent(actor, contentId);
        Set<String> statuses = statuses(requestedStatuses,
                template(visible.project().templateKey(), visible.project().templateVersion()));
        List<WorkItem> rows = workItems.findPage(visible.project().companyId(),
                visible.project().projectId(), contentId, statuses, page);
        long total = workItems.countPage(visible.project().companyId(),
                visible.project().projectId(), contentId, statuses);
        Map<UUID, MinimalUserSnapshot> people = people(visible.project().companyId(), rows);
        OffsetPageResponse<WorkItemSummary> response = OffsetPageResponse.of(rows.stream()
                .map(item -> summary(item, people.get(item.reporterUserId()))).toList(), page, total);
        return new WorkItemPage(response.items(), response.page(), response.size(),
                response.totalElements(), response.totalPages());
    }

    @Transactional(readOnly = true)
    public WorkItemDetail find(CurrentActor actor, UUID workItemId) {
        requireActor(actor);
        WorkItemLocator locator = workItems.findLocator(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        WorkItem item = workItems.find(project.companyId(), project.projectId(),
                        locator.contentId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return detail(item, users.findByUserId(project.companyId(), item.reporterUserId()).orElse(null));
    }

    public IdempotencyExecutionResult create(Create command) {
        VisibleContent visible = visibleContent(command.actor(), command.contentId());
        requireWritableAccess(visible.project().actorAccess());
        WorkItemPriority priority = priority(command.priority());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "createWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                    command.actor(), visible.project().projectId());
            requireWritableAccess(project.actorAccess());
            Content content = contents.lockForShare(project.companyId(), project.projectId(),
                            command.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            if (content.status() != ContentStatus.ACTIVE)
                throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "CONTENT_ARCHIVED");
            ProjectTemplateSnapshot template = template(project.templateKey(), project.templateVersion());
            ProjectTemplateSnapshot.WorkflowStatus initial = template.statuses().stream()
                    .filter(ProjectTemplateSnapshot.WorkflowStatus::initial).findFirst()
                    .orElseThrow(() -> ApplicationException.withReason(
                            StandardErrorCode.INVALID_STATE_TRANSITION, "TEMPLATE_INITIAL_STATUS_MISSING"));
            long sequence = workItems.nextSequence(project.companyId(), project.projectId());
            WorkItem item;
            try {
                item = WorkItem.create(UUID.randomUUID(), project.companyId(), project.projectId(),
                        content.id(), sequence, project.projectCode() + "-" + sequence,
                        content.workItemType(), command.title(), initial.statusCode(),
                        WorkItemStatusCategory.valueOf(initial.statusCategory()), priority,
                        command.description(), command.notes(), command.actor().userId(), clock.instant());
            } catch (IllegalArgumentException exception) {
                throw validation("body", "INVALID_WORK_ITEM", exception.getMessage());
            }
            if (!workItems.insert(item)) throw new IllegalStateException("work item insert failed");
            appendCreated(item, command.actor());
            MinimalUserSnapshot reporter = users.findByUserId(project.companyId(), item.reporterUserId())
                    .orElse(null);
            return stored(detail(item, reporter));
        });
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

    private static Set<String> statuses(Collection<String> requested,
            ProjectTemplateSnapshot template) {
        if (requested == null || requested.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>(requested);
        Set<String> allowed = template.statuses().stream()
                .map(ProjectTemplateSnapshot.WorkflowStatus::statusCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String status : normalized) {
            if (status == null || !allowed.contains(status))
                throw validation("status", "UNKNOWN_STATUS", "状态必须属于 Project 固定模板");
        }
        return Set.copyOf(normalized);
    }

    private Map<UUID, MinimalUserSnapshot> people(UUID companyId, List<WorkItem> rows) {
        return users.findByUserIds(companyId, rows.stream().map(WorkItem::reporterUserId).toList());
    }

    private static WorkItemSummary summary(WorkItem item, MinimalUserSnapshot reporter) {
        return new WorkItemSummary(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.type().name(), item.title(), item.statusCode(), item.statusCategory().name(),
                item.priority().name(), item.reporterUserId(), displayName(reporter), item.updatedAt());
    }

    private static WorkItemDetail detail(WorkItem item, MinimalUserSnapshot reporter) {
        return new WorkItemDetail(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.type().name(), item.title(), item.statusCode(), item.statusCategory().name(),
                item.priority().name(), item.reporterUserId(), displayName(reporter),
                item.description(), item.notes(), item.createdAt(), item.updatedAt());
    }

    private static String displayName(MinimalUserSnapshot reporter) {
        return reporter == null ? "未知成员" : reporter.displayName();
    }

    private void appendCreated(WorkItem item, CurrentActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", item.id()); payload.put("projectId", item.projectId());
        payload.put("contentId", item.contentId()); payload.put("itemNo", item.itemNo());
        payload.put("title", item.title()); payload.put("workItemType", item.type().name());
        payload.put("statusCode", item.statusCode());
        payload.put("statusCategory", item.statusCategory().name());
        payload.put("priority", item.priority().name());
        payload.put("reporterUserId", item.reporterUserId());
        payload.put("rowVersion", item.rowVersion());
        events.append(new EventDraft(CREATED, 1, "WorkItem", item.id(), item.rowVersion(),
                item.companyId(), EventActor.user(actor.userId()), objectMapper.valueToTree(payload)));
    }

    private StoredCommandResult stored(WorkItemDetail view) {
        try {
            return new StoredCommandResult(201, objectMapper.writeValueAsString(view), view.id(), null);
        } catch (JacksonException exception) {
            throw new IllegalStateException("work item response serialization failed", exception);
        }
    }

    private static WorkItemPriority priority(String value) {
        try { return WorkItemPriority.valueOf(value); }
        catch (RuntimeException exception) {
            throw validation("priority", "INVALID_VALUE", "优先级无效");
        }
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
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }

    private record VisibleContent(ProjectAccessSnapshot project, Content content) {}
}
