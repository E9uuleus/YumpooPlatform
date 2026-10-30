package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
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
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Command;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Field;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Outcome;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Result;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.RowResult;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Existing;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.ParentLink;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.ParentMode;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Plan;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.PlannedRow;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Ref;
import com.yumpoo.platform.workitem.application.WorkItemRelationRepository.ParentChildRelation;
import com.yumpoo.platform.workitem.domain.Content;
import com.yumpoo.platform.workitem.domain.ProjectSortKey;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRankPlacement;
import com.yumpoo.platform.workitem.domain.WorkItemRelation;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bulk import of work items. Rows are written straight to the repositories: no per-item domain
 * events or notifications, one security audit for the whole import.
 */
@Service
public class WorkItemImportService {
    private static final String IMPORT_REASON = "WORK_ITEM_IMPORT";

    private final WorkItemService core;
    private final WorkItemRepository workItems;
    private final WorkItemRelationRepository relations;
    private final ContentRepository contents;
    private final WorkItemLabelRepository labels;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final ProjectActiveMembershipQuery activeMemberships;
    private final IdempotentCommandExecutor idempotency;
    private final TimeTrackingRepository timeTracking;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkItemImportService(WorkItemService core, WorkItemRepository workItems,
            WorkItemRelationRepository relations, ContentRepository contents,
            WorkItemLabelRepository labels, ProjectAccessSnapshotQuery access,
            ProjectFactWriteGuard writeGuard, ProjectActiveMembershipQuery activeMemberships,
            IdempotentCommandExecutor idempotency, TimeTrackingRepository timeTracking,
            SecurityAuditAppendPort audits, ObjectMapper objectMapper, Clock clock) {
        this.core = core;
        this.workItems = workItems;
        this.relations = relations;
        this.contents = contents;
        this.labels = labels;
        this.access = access;
        this.writeGuard = writeGuard;
        this.activeMemberships = activeMemberships;
        this.idempotency = idempotency;
        this.timeTracking = timeTracking;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyExecutionResult importItems(Command command) {
        CurrentActor actor = command.actor();
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        ProjectAccessSnapshot visible = access.findVisible(actor, command.projectId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        WorkItemService.requireWritableAccess(visible.actorAccess());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(),
                "POST", "importWorkItems", command.idempotencyKey()), command.requestHash()),
                () -> run(command));
    }

    private StoredCommandResult run(Command command) {
        CurrentActor actor = command.actor();
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(actor, command.projectId());
        WorkItemService.requireWritableAccess(project.actorAccess());
        ItemWriteTarget target = ItemWriteTarget.from(project);
        List<WorkItemLabelModels.StatusLabel> statuses = labels.statuses(
                target.companyId(), target.projectId());
        List<WorkItemLabelModels.PriorityLabel> priorities = labels.priorities(
                target.companyId(), target.projectId());
        Set<UUID> requestedAssignees = command.rows().stream()
                .flatMap(row -> row.assigneeUserIds().stream()).collect(Collectors.toSet());
        List<ParentLink> links = relations.findActiveParentLinks(target.companyId(), target.projectId());
        Plan plan = WorkItemImportPlanner.plan(new WorkItemImportPlanner.Input(
                command.duplicateMode(), command.fields(), command.rows(),
                workItems.findImportTargets(target.companyId(), target.projectId()), links,
                new WorkItemImportPlanner.Catalog(
                        contents.findAll(target.companyId(), target.projectId()).stream()
                                .filter(content -> content.active() && content.deletedAt() == null)
                                .map(Content::id).collect(Collectors.toSet()),
                        statuses.stream().filter(WorkItemLabelModels.StatusLabel::active)
                                .map(WorkItemLabelModels.StatusLabel::code).collect(Collectors.toSet()),
                        priorities.stream().filter(WorkItemLabelModels.PriorityLabel::active)
                                .map(WorkItemLabelModels.PriorityLabel::code).collect(Collectors.toSet()),
                        requestedAssignees.isEmpty() ? Set.of() : activeMemberships.findActiveMemberIds(
                                target.companyId(), target.projectId(), requestedAssignees))));
        Map<Integer, WorkItem> created = Map.of();
        if (!command.dryRun()) {
            created = commit(command, target, plan, statuses, links);
            appendAudit(command, target, plan);
        }
        return stored(result(command.dryRun(), plan, created));
    }

    private Map<Integer, WorkItem> commit(Command command, ItemWriteTarget project, Plan plan,
            List<WorkItemLabelModels.StatusLabel> statuses, List<ParentLink> links) {
        CurrentActor actor = command.actor();
        List<PlannedRow> creates = plan.rows().stream()
                .filter(row -> row.outcome() == Outcome.CREATE).toList();
        List<PlannedRow> updates = plan.rows().stream()
                .filter(row -> row.outcome() == Outcome.UPDATE).toList();
        if (creates.isEmpty() && updates.isEmpty()) return Map.of();
        Map<String, WorkItemLabelModels.StatusLabel> statusByCode = statuses.stream()
                .collect(Collectors.toMap(WorkItemLabelModels.StatusLabel::code, label -> label));

        TreeSet<UUID> contentIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        creates.forEach(row -> contentIds.add(row.row().contentId()));
        if (command.fields().contains(Field.CONTENT))
            updates.stream().filter(row -> !row.row().contentId().equals(row.target().contentId()))
                    .forEach(row -> contentIds.add(row.row().contentId()));
        for (UUID contentId : contentIds) {
            Content content = core.lockCreationContent(project.companyId(), project.projectId(), contentId)
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            WorkItemService.requireActiveContent(content);
            core.markContentUsed(project, content, actor);
        }

        Set<String> lanes = new HashSet<>();
        creates.forEach(row -> lanes.add(statusCode(row)));
        if (command.fields().contains(Field.STATUS))
            for (PlannedRow row : updates) {
                if (statusCode(row).equals(row.target().statusCode())) continue;
                lanes.add(statusCode(row));
                lanes.add(row.target().statusCode());
            }
        if (!lanes.isEmpty()) workItems.lockRankLanes(project.companyId(), project.projectId(), lanes);

        String[] sortKeys = new String[creates.size()];
        long[] sequences = new long[creates.size()];
        if (!creates.isEmpty()) {
            workItems.lockProjectOrder(project.companyId(), project.projectId());
            String first = workItems.findFirstProjectRank(project.companyId(), project.projectId(),
                    UUID.randomUUID()).map(WorkItemRepository.RankedProjectWorkItem::rank).orElse(null);
            for (int index = 0; index < sortKeys.length; index++)
                sortKeys[index] = ProjectSortKey.evenlySpacedBetween(null, first, index + 1,
                                sortKeys.length)
                        .orElseThrow(() -> ApplicationException.withReason(
                                StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_ORDER_DENSE"));
            for (int index = 0; index < sequences.length; index++)
                sequences[index] = workItems.nextSequence(project.companyId(), project.projectId());
        }

        Map<UUID, WorkItem> locked = lockExisting(project, plan, updates);
        Map<UUID, UUID> dbParent = new HashMap<>();
        links.forEach(link -> dbParent.put(link.childId(), link.parentId()));

        Map<Integer, WorkItem> createdByRow = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (int index = creates.size() - 1; index >= 0; index--) {
            PlannedRow planned = creates.get(index);
            String code = statusCode(planned);
            WorkItemLabelModels.StatusLabel status = statusByCode.get(code);
            String rank = core.allocateRank(project.companyId(), project.projectId(), code,
                    WorkItemRankPlacement.START, null, null).rank();
            long sequence = sequences[index];
            WorkItem item;
            try {
                item = WorkItem.create(UUID.randomUUID(), project.companyId(), project.projectId(),
                        planned.row().contentId(), sequence, project.projectCode() + "-" + sequence,
                        planned.title(), code, WorkItemStatusCategory.valueOf(status.statusCategory()),
                        WorkItemImportPlanner.normalizePriority(planned.row().priority()), null, null,
                        planned.row().notes(), null, null, planned.row().dueDate(), (LocalTime) null,
                        rank, sortKeys[index], actor.userId(), now)
                        .assign(planned.row().assigneeUserIds(), actor.userId(), now)
                        .withoutCompletionTime();
                if (!workItems.insert(item)) throw new IllegalStateException("work item insert failed");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw rowFailure(planned, exception);
            }
            createdByRow.put(planned.index(), item);
        }

        for (PlannedRow planned : updates) {
            try {
                update(command, project, planned, locked.get(planned.target().id()), statusByCode);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw rowFailure(planned, exception);
            }
        }
        if (writeRelations(project, actor, plan, dbParent, createdByRow))
            timeTracking.advanceProjects(project.companyId(), List.of(project.projectId()));
        return createdByRow;
    }

    private Map<UUID, WorkItem> lockExisting(ItemWriteTarget project, Plan plan,
            List<PlannedRow> updates) {
        TreeSet<UUID> ids = new TreeSet<>(Comparator.comparing(UUID::toString));
        updates.forEach(row -> ids.add(row.target().id()));
        plan.rows().stream().filter(row -> row.parentMode() == ParentMode.SET
                        && row.parent().existingId() != null)
                .forEach(row -> ids.add(row.parent().existingId()));
        Map<UUID, WorkItem> locked = new HashMap<>();
        for (UUID id : ids) {
            locked.put(id, workItems.lockProjectItem(project.companyId(), project.projectId(), id)
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT)));
        }
        return locked;
    }

    private void update(Command command, ItemWriteTarget project, PlannedRow planned,
            WorkItem before, Map<String, WorkItemLabelModels.StatusLabel> statusByCode) {
        CurrentActor actor = command.actor();
        Set<Field> fields = command.fields();
        WorkItemImportModels.Row row = planned.row();
        Instant now = clock.instant();
        WorkItem current = before;
        WorkItem candidate = before.updateFields(before.title(),
                fields.contains(Field.PRIORITY)
                        ? WorkItemImportPlanner.normalizePriority(row.priority()) : before.priority(),
                before.assigneeUserId(), before.description(),
                fields.contains(Field.NOTES) ? row.notes() : before.notes(),
                before.timelineStartDate(), before.timelineEndDate(),
                fields.contains(Field.DUE_DATE) ? row.dueDate() : before.dueDate(),
                actor.userId(), now);
        if (fields.contains(Field.ASSIGNEES))
            candidate = candidate.assign(row.assigneeUserIds(), actor.userId(), now);
        if (fieldsChanged(before, candidate)) {
            if (!before.assigneeUserIds().equals(candidate.assigneeUserIds()))
                workItems.replaceAssignees(candidate);
            current = workItems.update(candidate, before.rowVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        }
        if (fields.contains(Field.STATUS) && !statusCode(planned).equals(current.statusCode())) {
            WorkItemLabelModels.StatusLabel status = statusByCode.get(statusCode(planned));
            String rank = core.allocateRank(project.companyId(), project.projectId(),
                    status.code(), WorkItemRankPlacement.START, null, current.id()).rank();
            WorkItem moved = current.move(status.code(),
                    WorkItemStatusCategory.valueOf(status.statusCategory()), rank,
                    actor.userId(), now);
            if (current.statusCategory() != WorkItemStatusCategory.DONE) moved = moved.withoutCompletionTime();
            current = workItems.transition(moved, current.rowVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        }
        if (fields.contains(Field.CONTENT) && !row.contentId().equals(current.contentId())) {
            WorkItem changed = current.changeContent(row.contentId(), actor.userId(), now);
            current = workItems.changeContent(changed, current.rowVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        }
    }

    private static boolean fieldsChanged(WorkItem before, WorkItem after) {
        return !java.util.Objects.equals(before.priority(), after.priority())
                || !java.util.Objects.equals(before.notes(), after.notes())
                || !java.util.Objects.equals(before.dueDate(), after.dueDate())
                || !before.assigneeUserIds().equals(after.assigneeUserIds());
    }

    /** Removes replaced or cleared parents first, then attaches the new ones. */
    private boolean writeRelations(ItemWriteTarget project, CurrentActor actor, Plan plan,
            Map<UUID, UUID> dbParent, Map<Integer, WorkItem> createdByRow) {
        Instant now = clock.instant();
        boolean changed = false;
        for (PlannedRow planned : plan.rows()) {
            if (planned.outcome() != Outcome.UPDATE || planned.parentMode() == ParentMode.KEEP) continue;
            UUID child = planned.target().id();
            UUID current = dbParent.get(child);
            UUID desired = planned.parentMode() == ParentMode.SET
                    ? idOf(planned.parent(), createdByRow) : null;
            if (current == null || current.equals(desired)) continue;
            WorkItemRelation relation = relations.findActiveParent(project.companyId(), child)
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            relations.softDelete(relation.delete(actor.userId(), IMPORT_REASON, now),
                            relation.rowVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            changed = true;
        }
        for (PlannedRow planned : plan.rows()) {
            if (planned.parentMode() != ParentMode.SET) continue;
            UUID child = planned.outcome() == Outcome.UPDATE ? planned.target().id()
                    : createdByRow.get(planned.index()).id();
            UUID parent = idOf(planned.parent(), createdByRow);
            if (planned.outcome() == Outcome.UPDATE && parent.equals(dbParent.get(child))) continue;
            try {
                if (!relations.insertParentChild(new ParentChildRelation(UUID.randomUUID(),
                        project.companyId(), parent, child, project.projectId(), actor.userId(), now)))
                    throw new IllegalStateException("work item parent-child relation insert failed");
            } catch (DataIntegrityViolationException exception) {
                throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                        "PARENT_CHILD_CONSTRAINT_VIOLATION");
            }
            changed = true;
        }
        return changed;
    }

    private static UUID idOf(Ref ref, Map<Integer, WorkItem> createdByRow) {
        return ref.existingId() != null ? ref.existingId() : createdByRow.get(ref.rowIndex()).id();
    }

    private static String statusCode(PlannedRow planned) {
        return planned.row().statusCode().strip();
    }

    private void appendAudit(Command command, ItemWriteTarget project, Plan plan) {
        CurrentActor actor = command.actor();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("duplicateMode", command.duplicateMode().name());
        summary.put("fields", command.fields().stream().map(Field::name).sorted().toList());
        summary.put("rowCount", plan.rows().size());
        summary.put("createdCount", plan.count(Outcome.CREATE));
        summary.put("updatedCount", plan.count(Outcome.UPDATE));
        summary.put("skippedCount", plan.count(Outcome.SKIP));
        summary.put("failedCount", plan.count(Outcome.ERROR));
        audits.append(new SecurityAuditDraft(project.companyId(),
                "work-item-import:" + command.idempotencyKey(), "WORK_ITEM_IMPORT",
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), actor.platformRoles().stream()
                        .map(Enum::name).collect(Collectors.toSet())),
                "PROJECT", project.projectId().toString(), command.duplicateMode().name(), null,
                objectMapper.valueToTree(summary), null, command.idempotencyKey(), null, null,
                clock.instant()));
    }

    private static Result result(boolean dryRun, Plan plan, Map<Integer, WorkItem> created) {
        List<RowResult> rows = new ArrayList<>(plan.rows().size());
        for (PlannedRow planned : plan.rows()) {
            WorkItem item = created.get(planned.index());
            UUID id = item != null ? item.id() : planned.target() == null ? null : planned.target().id();
            String itemNo = item != null ? item.itemNo()
                    : planned.target() == null ? null : planned.target().itemNo();
            rows.add(new RowResult(planned.row().rowNumber(), planned.outcome(), id, itemNo,
                    planned.issues()));
        }
        return new Result(dryRun, (int) plan.count(Outcome.CREATE), (int) plan.count(Outcome.UPDATE),
                (int) plan.count(Outcome.SKIP), (int) plan.count(Outcome.ERROR), List.copyOf(rows));
    }

    private StoredCommandResult stored(Result result) {
        try {
            return new StoredCommandResult(200, objectMapper.writeValueAsString(result), null, null);
        } catch (JacksonException exception) {
            throw new IllegalStateException("work item import response serialization failed", exception);
        }
    }

    /** The planner has already rejected every invalid input, so a domain rejection while writing is a defect: it surfaces as an internal error naming the file row. */
    private static IllegalStateException rowFailure(PlannedRow planned, RuntimeException cause) {
        return new IllegalStateException("work item import row " + planned.row().rowNumber() + " failed to commit", cause);
    }
}
