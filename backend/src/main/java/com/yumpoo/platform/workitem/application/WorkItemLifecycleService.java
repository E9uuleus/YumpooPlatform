package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.content.ContentRepository;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelRepository;
import com.yumpoo.platform.workitem.application.time.TimeTrackingRepository;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ProjectSortKey;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static com.yumpoo.platform.workitem.application.WorkItemCommands.Delete;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.Restore;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedProjectWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemEvents.lifecycleEventPayload;
import static com.yumpoo.platform.workitem.application.WorkItemRules.invalidLifecycle;
import static com.yumpoo.platform.workitem.application.WorkItemRules.normalizeDeleteReason;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireActor;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireVersion;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireWritableAccess;

@Service
public class WorkItemLifecycleService {
    private final WorkItemRepository workItems;
    private final ContentRepository contents;
    private final ProjectFactWriteGuard writeGuard;
    private final WorkItemLabelRepository labels;
    private final IdempotentCommandExecutor idempotency;
    private final Clock clock;
    private final TimeTrackingRepository timeTracking;
    private final WorkItemOrderingService ordering;
    private final WorkItemViews views;
    private final WorkItemEvents workItemEvents;

    public WorkItemLifecycleService(WorkItemRepository workItems, ContentRepository contents, ProjectFactWriteGuard writeGuard,
            WorkItemLabelRepository labels, IdempotentCommandExecutor idempotency, Clock clock,
            TimeTrackingRepository timeTracking, WorkItemOrderingService ordering, WorkItemViews views,
            WorkItemEvents workItemEvents) {
        this.workItems = workItems;
        this.contents = contents;
        this.writeGuard = writeGuard;
        this.labels = labels;
        this.idempotency = idempotency;
        this.clock = clock;
        this.timeTracking = timeTracking;
        this.ordering = ordering;
        this.views = views;
        this.workItemEvents = workItemEvents;
    }

    public IdempotencyExecutionResult delete(Delete command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocatorIncludingDeleted(
                        command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        String reason = normalizeDeleteReason(command.reason());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "DELETE", "deleteWorkItem", command.idempotencyKey()),
                command.requestHash()), () -> delete(command, locator, reason));
    }

    public IdempotencyExecutionResult archive(WorkItemCommands.Archive command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireWritableAccess(views.visible(command.actor(), locator.projectId()).actorAccess());
        String operation = command.archived() ? "archiveWorkItem" : "unarchiveWorkItem";
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", operation, command.idempotencyKey()), command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(), locator.projectId());
            requireWritableAccess(project.actorAccess());
            contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            WorkItem before = workItems.lockIncludingDeleted(project.companyId(), project.projectId(),
                            locator.contentId(), command.workItemId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireVersion(before, command.expectedVersion());
            if (before.deleted()) throw invalidLifecycle("WORK_ITEM_ALREADY_DELETED");
            WorkItem after = before;
            if (before.archived() != command.archived()) {
                after = workItems.archive(before.archive(command.archived(), command.actor().userId(), clock.instant()),
                                command.expectedVersion())
                        .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
                Map<String, Object> payload = lifecycleEventPayload(after);
                payload.put("archived", after.archived());
                workItemEvents.append(command.archived() ? "workitem.work_item_archived" : "workitem.work_item_unarchived",
                        after, command.actor(), payload);
            }
            return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
                    labels.statuses(project.companyId(), project.projectId())));
        });
    }

    private StoredCommandResult delete(Delete command, WorkItemLocator locator, String reason) {
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
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
        workItemEvents.appendDeleted(after, command.actor());
        timeTracking.advanceProjects(project.companyId(), List.of(project.projectId()));
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
                labels.statuses(project.companyId(), project.projectId())));
    }

    public IdempotencyExecutionResult restore(Restore command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocatorIncludingDeleted(
                        command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
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
        WorkItem snapshot = workItems.findIncludingDeleted(project.companyId(), project.projectId(),
                        content.id(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(snapshot, command.expectedVersion());
        if (!snapshot.deleted()) throw invalidLifecycle("WORK_ITEM_NOT_DELETED");
        workItems.lockRankLanes(project.companyId(), project.projectId(), List.of(snapshot.statusCode()));
        WorkItem before = workItems.lockIncludingDeleted(project.companyId(), project.projectId(),
                        content.id(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        if (!before.deleted()) throw invalidLifecycle("WORK_ITEM_NOT_DELETED");
        List<RankedWorkItem> lane = workItems.findRankOrder(project.companyId(), project.projectId(),
                before.statusCode());
        boolean originalRankAvailable = lane.stream().noneMatch(item -> item.rank().equals(before.rank()));
        String rank = originalRankAvailable ? before.rank()
                : ordering.allocateRank(project.companyId(), project.projectId(),
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
        workItemEvents.appendRestored(after, command.actor());
        timeTracking.advanceProjects(project.companyId(), List.of(project.projectId()));
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
                labels.statuses(project.companyId(), project.projectId())));
    }
}
