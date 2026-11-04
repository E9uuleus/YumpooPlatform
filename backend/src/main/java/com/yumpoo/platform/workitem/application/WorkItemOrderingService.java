package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.content.ContentRepository;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelModels;
import com.yumpoo.platform.workitem.application.label.WorkItemLabelRepository;
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
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.KanbanRank;
import com.yumpoo.platform.workitem.domain.ProjectSortKey;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemCommands.RankMove;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.ProjectOrderMove;
import static com.yumpoo.platform.workitem.application.WorkItemCommands.SubitemOrderMove;
import static com.yumpoo.platform.workitem.application.WorkItemModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemRepository.RankedProjectWorkItem;
import static com.yumpoo.platform.workitem.application.WorkItemRules.invalidProjectOrderAnchor;
import static com.yumpoo.platform.workitem.application.WorkItemRules.invalidRankAnchor;
import static com.yumpoo.platform.workitem.application.WorkItemRules.normalizeResolution;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireActor;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireVersion;
import static com.yumpoo.platform.workitem.application.WorkItemRules.requireWritableAccess;
import static com.yumpoo.platform.workitem.application.WorkItemRules.validation;
import static com.yumpoo.platform.workitem.application.WorkItemViews.selectableStatus;
import static com.yumpoo.platform.workitem.application.WorkItemViews.status;

@Service
public class WorkItemOrderingService {
    private final WorkItemRepository workItems;
    private final WorkItemRelationRepository relations;
    private final ContentRepository contents;
    private final ProjectFactWriteGuard writeGuard;
    private final WorkItemLabelRepository labels;
    private final IdempotentCommandExecutor idempotency;
    private final Clock clock;
    private final WorkItemViews views;
    private final WorkItemEvents workItemEvents;

    public WorkItemOrderingService(WorkItemRepository workItems, WorkItemRelationRepository relations, ContentRepository contents,
            ProjectFactWriteGuard writeGuard, WorkItemLabelRepository labels, IdempotentCommandExecutor idempotency,
            Clock clock, WorkItemViews views, WorkItemEvents workItemEvents) {
        this.workItems = workItems;
        this.relations = relations;
        this.contents = contents;
        this.writeGuard = writeGuard;
        this.labels = labels;
        this.idempotency = idempotency;
        this.clock = clock;
        this.views = views;
        this.workItemEvents = workItemEvents;
    }

    public IdempotencyExecutionResult rankMove(RankMove command) {
        requireActor(command.actor());
        WorkItemLocator locator = workItems.findLocator(command.actor().companyId(), command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
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
        ProjectAccessSnapshot project = views.visible(command.actor(), locator.projectId());
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
        ProjectAccessSnapshot project = views.visible(command.actor(), parent.projectId());
        requireWritableAccess(project.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "moveWorkItemSubitemOrder",
                command.idempotencyKey()), command.requestHash()),
                () -> projectOrderMove(command.actor(), child, command.subitemId(),
                        command.expectedVersion(), command.previousVisibleWorkItemId(),
                        command.nextVisibleWorkItemId(), command.parentWorkItemId()));
    }

    private StoredCommandResult rankMove(RankMove command, WorkItemLocator locator) {
        WorkItemRankPlacement placement = placement(command.placement(), command.anchorWorkItemId());
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(
                command.actor(), locator.projectId());
        requireWritableAccess(project.actorAccess());
        Content content = contents.lockForShare(project.companyId(), project.projectId(), locator.contentId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
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
        workItems.lockRankLanes(project.companyId(), project.projectId(),
                List.of(snapshot.statusCode(), target.code()));
        WorkItem before = workItems.lock(project.companyId(), project.projectId(), content.id(),
                        command.workItemId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireVersion(before, command.expectedVersion());
        if (!before.statusCode().equals(snapshot.statusCode()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        RankAllocation allocation = allocateRank(project.companyId(), project.projectId(),
                target.code(), placement, command.anchorWorkItemId(), before.id());
        if (allocation.noOp())
            return views.stored(200, views.detail(before, views.people(project.companyId(), List.of(before)), true,
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
            workItemEvents.appendRankChanged(after, command.actor(), placement, command.anchorWorkItemId());
        } else {
            workItemEvents.appendStatusChanged(before, after, command.actor(), resolution);
        }
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
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
                        .orElseThrow(WorkItemRules::invalidProjectOrderAnchor);
        WorkItem next = nextVisibleWorkItemId == null ? null
                : workItems.lockProjectItem(project.companyId(), project.projectId(),
                                nextVisibleWorkItemId)
                        .orElseThrow(WorkItemRules::invalidProjectOrderAnchor);
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
                        .orElseThrow(WorkItemRules::invalidProjectOrderAnchor);
                lower = previous.projectSortKey();
                upper = workItems.findProjectNeighborAfter(project.companyId(), project.projectId(),
                                lower, moving.id()).map(RankedProjectWorkItem::rank).orElse(null);
            } else {
                next = workItems.lockProjectItem(project.companyId(), project.projectId(), next.id())
                        .orElseThrow(WorkItemRules::invalidProjectOrderAnchor);
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
        return views.stored(200, views.detail(after, views.people(project.companyId(), List.of(after)), true,
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

    RankAllocation allocateRank(UUID companyId, UUID projectId,
            String statusCode, WorkItemRankPlacement placement, UUID anchorWorkItemId,
            UUID movingWorkItemId) {
        List<RankedWorkItem> lane = workItems.findRankOrder(companyId, projectId, statusCode);
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
            workItems.rewriteRanks(companyId, projectId, statusCode, rebalanced);
            lane = workItems.findRankOrder(companyId, projectId, statusCode);
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

    record RankAllocation(String rank, boolean noOp) {}
}
