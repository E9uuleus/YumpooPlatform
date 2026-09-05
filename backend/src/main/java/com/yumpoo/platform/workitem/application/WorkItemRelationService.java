package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
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
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import com.yumpoo.platform.workitem.application.WorkItemRelationCommands.ChangeParent;
import com.yumpoo.platform.workitem.application.WorkItemRelationCommands.Create;
import com.yumpoo.platform.workitem.application.WorkItemRelationCommands.Delete;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.ActiveParent;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.Candidate;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.CandidatePage;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.Capabilities;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.Counterpart;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.RelationPage;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.RelationView;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemRelation;
import com.yumpoo.platform.workitem.domain.WorkItemRelationRole;
import com.yumpoo.platform.workitem.domain.WorkItemRelationType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorkItemRelationService {
    private static final String CREATED = "workitem.work_item_relation_created";
    private static final String DELETED = "workitem.work_item_relation_deleted";
    private static final String PARENT_CHANGED = "workitem.work_item_parent_changed";

    private final WorkItemRelationRepository relations;
    private final WorkItemRepository workItems;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkItemRelationService(WorkItemRelationRepository relations,
            WorkItemRepository workItems, ProjectAccessSnapshotQuery access,
            ProjectFactWriteGuard writeGuard, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, ObjectMapper objectMapper, Clock clock) {
        this.relations = relations;
        this.workItems = workItems;
        this.access = access;
        this.writeGuard = writeGuard;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RelationView find(CurrentActor actor, UUID relationId) {
        requireActor(actor);
        WorkItemRelation relation = relations.findById(actor.companyId(), relationId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        Map<UUID, ProjectAccessSnapshot> projects = visible(actor,
                projectIds(relation.leftProjectId(), relation.rightProjectId()));
        return requiredView(actor.companyId(), relationId, relation.leftWorkItemId(),
                projects.values().stream().allMatch(WorkItemRelationService::canWrite));
    }

    @Transactional(readOnly = true)
    public RelationPage list(CurrentActor actor, UUID workItemId,
            String relationTypeValue, OffsetPageRequest page) {
        WorkItemRelationType relationType = relationTypeValue == null ? null
                : enumValue("relationType", relationTypeValue, WorkItemRelationType.class);
        WorkItemLocator locator = activeLocator(actor, workItemId);
        ProjectAccessSnapshot project = visible(actor, locator.projectId());
        Set<UUID> counterpartProjectIds = relations.findCounterpartProjectIds(
                project.companyId(), workItemId);
        Map<UUID, ProjectAccessSnapshot> visibleProjects = access.findVisible(actor,
                counterpartProjectIds);
        Set<UUID> visibleProjectIds = new HashSet<>(visibleProjects.keySet());
        visibleProjectIds.add(project.projectId());
        List<RelationView> items = relations.findActiveForWorkItem(project.companyId(), workItemId,
                        relationType, visibleProjectIds, page).stream()
                .map(projection -> view(projection, workItemId,
                        canWrite(project) && canWrite(visibleProjects.get(
                                counterpartProjectId(projection.relation(), workItemId)))))
                .toList();
        long total = relations.countActiveForWorkItem(project.companyId(), workItemId,
                relationType, visibleProjectIds);
        boolean hasHiddenRelations = relations.hasHiddenForWorkItem(project.companyId(),
                workItemId, visibleProjectIds);
        return new RelationPage(items, page.page(), page.size(), total, totalPages(total, page.size()),
                canWrite(project), hasHiddenRelations);
    }

    @Transactional(readOnly = true)
    public CandidatePage candidates(CurrentActor actor, UUID workItemId,
            String relationTypeValue, String currentRoleValue,
            UUID targetProjectId, String query, OffsetPageRequest page) {
        WorkItemRelationType relationType = enumValue(
                "relationType", relationTypeValue, WorkItemRelationType.class);
        WorkItemRelationRole currentRole = enumValue(
                "currentRole", currentRoleValue, WorkItemRelationRole.class);
        validateRole(relationType, currentRole);
        String normalizedQuery = normalizeQuery(query);
        WorkItemLocator locator = activeLocator(actor, workItemId);
        ProjectAccessSnapshot currentProject = visible(actor, locator.projectId());
        UUID effectiveTargetProjectId = targetProjectId == null
                ? currentProject.projectId() : targetProjectId;
        ProjectAccessSnapshot targetProject = visible(actor, effectiveTargetProjectId);
        requireWritable(currentProject);
        requireWritable(targetProject);
        requireSameProjectForParentChild(relationType, currentProject.projectId(),
                targetProject.projectId());
        List<Candidate> items = relations.findCandidates(currentProject.companyId(),
                        targetProject.projectId(),
                        workItemId, normalizedQuery, relationType, currentRole.leftSide(), page).stream()
                .map(this::candidate).toList();
        long total = relations.countCandidates(currentProject.companyId(), targetProject.projectId(),
                workItemId, normalizedQuery);
        return new CandidatePage(items, page.page(), page.size(), total,
                totalPages(total, page.size()));
    }

    public IdempotencyExecutionResult create(Create command) {
        requireActor(command.actor());
        WorkItemRelationType relationType = enumValue(
                "relationType", command.relationType(), WorkItemRelationType.class);
        WorkItemRelationRole currentRole = enumValue(
                "currentRole", command.currentRole(), WorkItemRelationRole.class);
        validateRole(relationType, currentRole);
        if (command.currentWorkItemId().equals(command.targetWorkItemId()))
            throw invalid("targetWorkItemId", "SELF_RELATION_NOT_ALLOWED", "事项不能关联自身");
        WorkItemLocator current = activeLocator(command.actor(), command.currentWorkItemId());
        ProjectAccessSnapshot currentProject = visible(command.actor(), current.projectId());
        UUID targetProjectId = command.targetProjectId() == null
                ? current.projectId() : command.targetProjectId();
        ProjectAccessSnapshot targetProject = visible(command.actor(), targetProjectId);
        requireWritable(currentProject);
        requireWritable(targetProject);
        requireSameProjectForParentChild(relationType, current.projectId(), targetProjectId);
        WorkItemLocator target = activeLocator(command.actor(), targetProjectId,
                command.targetWorkItemId());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "createWorkItemRelation",
                command.idempotencyKey()), command.requestHash()), () -> {
            Map<UUID, ProjectFactWriteSnapshot> projects = lockProjects(command.actor(),
                    projectIds(current.projectId(), target.projectId()));
            Map<UUID, WorkItem> locked = lockItems(projects, current, target);
            Pair pair = pair(relationType, currentRole,
                    command.currentWorkItemId(), command.targetWorkItemId());
            WorkItemRelation existing = relations.findActivePair(command.actor().companyId(), pair.type(),
                    pair.leftWorkItemId(), pair.rightWorkItemId()).orElse(null);
            if (existing != null) return stored(200, requiredView(command.actor().companyId(), existing.id(),
                    command.currentWorkItemId(), true));
            validateParentChild(command.actor().companyId(), pair, null);
            WorkItemRelation relation = WorkItemRelation.create(UUID.randomUUID(),
                    command.actor().companyId(),
                    pair.type(), pair.leftWorkItemId(), pair.rightWorkItemId(),
                    locked.get(pair.leftWorkItemId()).projectId(),
                    locked.get(pair.rightWorkItemId()).projectId(), command.actor().userId(),
                    clock.instant());
            try {
                if (!relations.insert(relation)) throw new IllegalStateException("relation insert failed");
            } catch (DataIntegrityViolationException exception) {
                WorkItemRelation duplicate = relations.findActivePair(command.actor().companyId(), pair.type(),
                        pair.leftWorkItemId(), pair.rightWorkItemId()).orElse(null);
                if (duplicate != null) return stored(200, requiredView(command.actor().companyId(), duplicate.id(),
                        command.currentWorkItemId(), true));
                throw conflict(pair.type() == WorkItemRelationType.PARENT_CHILD
                        ? "PARENT_CHILD_CONSTRAINT_VIOLATION" : "RELATION_ALREADY_ACTIVE");
            }
            appendCreated(relation, command.actor());
            return stored(201, requiredView(command.actor().companyId(), relation.id(),
                    command.currentWorkItemId(), true));
        });
    }

    public IdempotencyExecutionResult changeParent(ChangeParent command) {
        requireActor(command.actor());
        WorkItemRelation snapshot = relations.findById(command.actor().companyId(), command.relationId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (snapshot.relationType() != WorkItemRelationType.PARENT_CHILD)
            throw conflict("RELATION_IS_NOT_PARENT_CHILD");
        visible(command.actor(), snapshot.leftProjectId());
        WorkItemLocator newParent = activeLocator(command.actor(), command.newParentWorkItemId());
        if (!snapshot.leftProjectId().equals(newParent.projectId())) {
            visible(command.actor(), newParent.projectId());
            throw invalid("newParentWorkItemId", "CROSS_PROJECT_RELATION_NOT_SUPPORTED",
                    "M2-21 只允许同项目关系");
        }
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "POST", "changeWorkItemParent",
                command.idempotencyKey()), command.requestHash()), () -> {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(command.actor(),
                    snapshot.leftProjectId());
            requireWritable(project);
            WorkItemLocator child = workItems.findLocator(project.companyId(), snapshot.rightWorkItemId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            lockItems(project, newParent, child);
            WorkItemRelation before = relations.lock(project.companyId(), command.relationId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireVersion(before, command.expectedVersion());
            if (!before.active()) throw conflict("RELATION_NOT_ACTIVE");
            if (before.relationType() != WorkItemRelationType.PARENT_CHILD)
                throw conflict("RELATION_IS_NOT_PARENT_CHILD");
            if (before.leftWorkItemId().equals(command.newParentWorkItemId()))
                return stored(200, requiredView(project.companyId(), before.id(),
                        before.rightWorkItemId(), true));
            Pair replacement = new Pair(WorkItemRelationType.PARENT_CHILD,
                    command.newParentWorkItemId(), before.rightWorkItemId());
            validateParentChild(project.companyId(), replacement, before.id());
            Instant now = clock.instant();
            WorkItemRelation deleted = relations.softDelete(before.delete(command.actor().userId(),
                            command.reason(), now), command.expectedVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            WorkItemRelation after = WorkItemRelation.create(UUID.randomUUID(), project.companyId(),
                    WorkItemRelationType.PARENT_CHILD, command.newParentWorkItemId(),
                    before.rightWorkItemId(), project.projectId(), project.projectId(),
                    command.actor().userId(), now);
            try {
                if (!relations.insert(after)) throw new IllegalStateException("parent relation insert failed");
            } catch (DataIntegrityViolationException exception) {
                throw conflict("PARENT_CHILD_CONSTRAINT_VIOLATION");
            }
            appendParentChanged(deleted, after, command.actor());
            return stored(200, requiredView(project.companyId(), after.id(),
                    after.rightWorkItemId(), true));
        });
    }

    public IdempotencyExecutionResult delete(Delete command) {
        requireActor(command.actor());
        WorkItemRelation snapshot = relations.findById(command.actor().companyId(), command.relationId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        Map<UUID, ProjectAccessSnapshot> visibleProjects = visible(command.actor(),
                projectIds(snapshot.leftProjectId(), snapshot.rightProjectId()));
        visibleProjects.values().forEach(WorkItemRelationService::requireWritable);
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(
                command.actor().userId(), "DELETE", "deleteWorkItemRelation",
                command.idempotencyKey()), command.requestHash()), () -> {
            Map<UUID, ProjectFactWriteSnapshot> projects = lockProjects(command.actor(),
                    projectIds(snapshot.leftProjectId(), snapshot.rightProjectId()));
            lockRelationEndpoints(projects, snapshot);
            WorkItemRelation before = relations.lock(command.actor().companyId(), command.relationId())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            requireVersion(before, command.expectedVersion());
            if (!before.active()) throw conflict("RELATION_NOT_ACTIVE");
            WorkItemRelation after = relations.softDelete(before.delete(command.actor().userId(),
                            command.reason(), clock.instant()), command.expectedVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            appendDeleted(after, command.actor());
            return stored(200, requiredView(command.actor().companyId(), after.id(),
                    after.leftWorkItemId(), false));
        });
    }

    private Candidate candidate(WorkItemRelationRepository.CandidateFacts facts) {
        if (facts.alreadyRelated()) return new Candidate(counterpart(facts.item()), "INELIGIBLE",
                "ALREADY_RELATED", null);
        if (facts.parentIsChild()) return new Candidate(counterpart(facts.item()), "INELIGIBLE",
                "PARENT_IS_CHILD", null);
        if (facts.childHasChildren()) return new Candidate(counterpart(facts.item()), "INELIGIBLE",
                "CHILD_HAS_CHILDREN", null);
        if (facts.activeParentRelationId() == null)
            return new Candidate(counterpart(facts.item()), "ELIGIBLE", null, null);
        ActiveParent parent = new ActiveParent(facts.activeParentRelationId(),
                StrongEtag.format(facts.activeParentVersion()), counterpart(facts.activeParentItem()));
        return new Candidate(counterpart(facts.item()), "REPARENT_REQUIRED",
                "CHILD_ALREADY_HAS_PARENT", parent);
    }

    private void validateParentChild(UUID companyId, Pair pair, UUID replacedRelationId) {
        if (pair.type() != WorkItemRelationType.PARENT_CHILD) return;
        if (relations.hasActiveParent(companyId, pair.leftWorkItemId()))
            throw conflict("PARENT_IS_CHILD");
        if (relations.hasActiveChildren(companyId, pair.rightWorkItemId()))
            throw conflict("CHILD_HAS_CHILDREN");
        WorkItemRelation parent = relations.findActiveParent(companyId, pair.rightWorkItemId())
                .orElse(null);
        if (parent != null && !parent.id().equals(replacedRelationId))
            throw conflict("CHILD_ALREADY_HAS_PARENT");
    }

    private Map<UUID, WorkItem> lockItems(ProjectFactWriteSnapshot project,
            WorkItemLocator first, WorkItemLocator second) {
        return lockItems(Map.of(project.projectId(), project), first, second);
    }

    private Map<UUID, WorkItem> lockItems(Map<UUID, ProjectFactWriteSnapshot> projects,
            WorkItemLocator first, WorkItemLocator second) {
        Map<UUID, WorkItem> result = new LinkedHashMap<>();
        Map<UUID, List<WorkItemLocator>> grouped = List.of(first, second).stream().distinct()
                .collect(Collectors.groupingBy(WorkItemLocator::projectId));
        for (UUID projectId : grouped.keySet().stream()
                .sorted(Comparator.comparing(UUID::toString)).toList()) {
            ProjectFactWriteSnapshot project = projects.get(projectId);
            if (project == null) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
            for (WorkItemLocator locator : grouped.get(projectId).stream()
                    .sorted(Comparator.comparing(item -> item.workItemId().toString())).toList()) {
                WorkItem item = workItems.lockProjectItem(project.companyId(), projectId,
                                locator.workItemId())
                        .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
                result.put(locator.workItemId(), item);
            }
        }
        return result;
    }

    private void lockRelationEndpoints(Map<UUID, ProjectFactWriteSnapshot> projects,
            WorkItemRelation relation) {
        Map<UUID, List<UUID>> endpoints = new LinkedHashMap<>();
        endpoints.computeIfAbsent(relation.leftProjectId(), ignored -> new java.util.ArrayList<>())
                .add(relation.leftWorkItemId());
        endpoints.computeIfAbsent(relation.rightProjectId(), ignored -> new java.util.ArrayList<>())
                .add(relation.rightWorkItemId());
        for (UUID projectId : endpoints.keySet().stream()
                .sorted(Comparator.comparing(UUID::toString)).toList()) {
            ProjectFactWriteSnapshot project = projects.get(projectId);
            if (project == null) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
            for (UUID workItemId : endpoints.get(projectId).stream().distinct()
                    .sorted(Comparator.comparing(UUID::toString)).toList()) {
                WorkItemLocator endpoint = workItems.findLocatorIncludingDeleted(project.companyId(),
                                workItemId)
                        .filter(locator -> locator.projectId().equals(projectId))
                        .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
                workItems.lockIncludingDeleted(project.companyId(), endpoint.projectId(),
                                endpoint.contentId(), endpoint.workItemId())
                        .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            }
        }
    }

    private Map<UUID, ProjectFactWriteSnapshot> lockProjects(CurrentActor actor,
            Set<UUID> projectIds) {
        Map<UUID, ProjectFactWriteSnapshot> result = new LinkedHashMap<>();
        for (UUID projectId : projectIds.stream()
                .sorted(Comparator.comparing(UUID::toString)).toList()) {
            ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(actor, projectId);
            requireWritable(project);
            result.put(projectId, project);
        }
        return result;
    }

    private static void requireSameProjectForParentChild(WorkItemRelationType relationType,
            UUID currentProjectId, UUID targetProjectId) {
        if (relationType == WorkItemRelationType.PARENT_CHILD
                && !currentProjectId.equals(targetProjectId)) {
            throw invalid("targetProjectId", "PARENT_CHILD_REQUIRES_SAME_PROJECT",
                    "父子关系必须位于同一项目");
        }
    }

    private WorkItemLocator activeLocator(CurrentActor actor, UUID workItemId) {
        requireActor(actor);
        return workItems.findLocator(actor.companyId(), workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private WorkItemLocator activeLocator(CurrentActor actor, UUID projectId, UUID workItemId) {
        requireActor(actor);
        return workItems.findLocator(actor.companyId(), projectId, workItemId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private Map<UUID, ProjectAccessSnapshot> visible(CurrentActor actor, Set<UUID> projectIds) {
        Map<UUID, ProjectAccessSnapshot> result = access.findVisible(actor, projectIds);
        if (!result.keySet().containsAll(projectIds))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        return result;
    }

    private static UUID counterpartProjectId(WorkItemRelation relation, UUID currentWorkItemId) {
        return relation.leftWorkItemId().equals(currentWorkItemId)
                ? relation.rightProjectId() : relation.leftProjectId();
    }

    private static Set<UUID> projectIds(UUID first, UUID second) {
        Set<UUID> result = new HashSet<>();
        result.add(first);
        result.add(second);
        return result;
    }

    private static Pair pair(WorkItemRelationType type, WorkItemRelationRole role,
            UUID currentId, UUID targetId) {
        validateRole(type, role);
        if (type == WorkItemRelationType.RELATED) {
            return currentId.toString().compareTo(targetId.toString()) <= 0
                    ? new Pair(type, currentId, targetId) : new Pair(type, targetId, currentId);
        }
        return role.leftSide() ? new Pair(type, currentId, targetId)
                : new Pair(type, targetId, currentId);
    }

    private static <T extends Enum<T>> T enumValue(String field, String value, Class<T> type) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw ApplicationException.validation(new FieldViolation(field, "INVALID_ENUM_VALUE",
                    field + " 不是受支持的值"));
        }
    }

    private RelationView requiredView(UUID companyId, UUID relationId, UUID currentWorkItemId,
            boolean writable) {
        return view(relations.findProjection(companyId, relationId)
                        .orElseThrow(() -> new IllegalStateException("relation projection missing")),
                currentWorkItemId, writable);
    }

    private static RelationView view(WorkItemRelationRepository.Projection projection,
            UUID currentWorkItemId, boolean writable) {
        WorkItemRelation relation = projection.relation();
        boolean currentIsLeft = relation.leftWorkItemId().equals(currentWorkItemId);
        WorkItemRelationRole role = role(relation.relationType(), currentIsLeft);
        WorkItemRelationRepository.Endpoint endpoint = currentIsLeft ? projection.right() : projection.left();
        boolean active = relation.active();
        return new RelationView(relation.id(), relation.relationType().name(), role.name(),
                role.counterpart().name(), true, counterpart(endpoint), active ? "ACTIVE" : "DELETED",
                relation.createdByUserId(), relation.createdAt(), relation.deletedByUserId(),
                relation.deletedAt(), relation.deleteReason(), relation.rowVersion(),
                StrongEtag.format(relation.rowVersion()), new Capabilities(writable && active,
                        writable && active && relation.relationType() == WorkItemRelationType.PARENT_CHILD));
    }

    private static WorkItemRelationRole role(WorkItemRelationType type, boolean left) {
        return switch (type) {
            case PARENT_CHILD -> left ? WorkItemRelationRole.PARENT : WorkItemRelationRole.CHILD;
            case RELATED -> WorkItemRelationRole.RELATED;
            case BLOCKS -> left ? WorkItemRelationRole.BLOCKS : WorkItemRelationRole.BLOCKED_BY;
            case SOURCE -> left ? WorkItemRelationRole.SOURCE : WorkItemRelationRole.DERIVED_FROM;
            case DUPLICATE -> left ? WorkItemRelationRole.DUPLICATE_OF : WorkItemRelationRole.CANONICAL;
        };
    }

    private static Counterpart counterpart(WorkItemRelationRepository.Endpoint endpoint) {
        return new Counterpart(endpoint.id(), endpoint.projectId(), endpoint.contentId(),
                endpoint.itemNo(), endpoint.contentName(), endpoint.contentColorToken(),
                endpoint.title(), endpoint.statusCode(),
                endpoint.deleted());
    }

    private void appendCreated(WorkItemRelation relation, CurrentActor actor) {
        append(CREATED, relation, actor, relationPayload(relation));
    }

    private void appendDeleted(WorkItemRelation relation, CurrentActor actor) {
        Map<String, Object> payload = relationPayload(relation);
        payload.put("deletedAt", relation.deletedAt());
        append(DELETED, relation, actor, payload);
    }

    private void appendParentChanged(WorkItemRelation before, WorkItemRelation after,
            CurrentActor actor) {
        Map<String, Object> payload = relationPayload(after);
        payload.put("oldRelationId", before.id());
        payload.put("oldParentWorkItemId", before.leftWorkItemId());
        payload.put("newParentWorkItemId", after.leftWorkItemId());
        payload.put("childWorkItemId", after.rightWorkItemId());
        append(PARENT_CHANGED, after, actor, payload);
    }

    private void append(String eventType, WorkItemRelation relation, CurrentActor actor,
            Map<String, Object> payload) {
        events.append(new EventDraft(eventType, 1, "WorkItemRelation", relation.id(),
                relation.rowVersion(), relation.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private static Map<String, Object> relationPayload(WorkItemRelation relation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("relationId", relation.id());
        payload.put("relationType", relation.relationType().name());
        payload.put("leftWorkItemId", relation.leftWorkItemId());
        payload.put("rightWorkItemId", relation.rightWorkItemId());
        payload.put("leftProjectId", relation.leftProjectId());
        payload.put("rightProjectId", relation.rightProjectId());
        return payload;
    }

    private StoredCommandResult stored(int status, RelationView view) {
        try {
            return new StoredCommandResult(status, objectMapper.writeValueAsString(view), view.id(),
                    view.etag());
        } catch (JacksonException exception) {
            throw new IllegalStateException("relation response serialization failed", exception);
        }
    }

    private static void validateRole(WorkItemRelationType type, WorkItemRelationRole role) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(role);
        if (role.relationType() != type)
            throw invalid("currentRole", "ROLE_TYPE_MISMATCH", "当前侧语义与关系类型不匹配");
    }

    private static String normalizeQuery(String query) {
        String value = query == null ? "" : query.strip();
        if (value.isEmpty() || value.length() > 80)
            throw invalid("q", "INVALID_LENGTH", "关系候选关键字长度必须为 1 到 80 个字符");
        return value;
    }

    private static boolean canWrite(ProjectAccessSnapshot project) {
        return project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }

    private static void requireWritable(ProjectAccessSnapshot project) {
        if (project.actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.lifecycle() == ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED)
            throw conflict("PROJECT_ARCHIVED");
    }

    private static void requireWritable(ProjectFactWriteSnapshot project) {
        if (project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.lifecycle() == ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED)
            throw conflict("PROJECT_ARCHIVED");
    }

    private static void requireVersion(WorkItemRelation relation, long expectedVersion) {
        if (relation.rowVersion() != expectedVersion)
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }

    private static int totalPages(long total, int size) {
        return Math.toIntExact((total + size - 1) / size);
    }

    private static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException conflict(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }

    private static ApplicationException invalid(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }

    private record Pair(WorkItemRelationType type, UUID leftWorkItemId, UUID rightWorkItemId) {}
}
