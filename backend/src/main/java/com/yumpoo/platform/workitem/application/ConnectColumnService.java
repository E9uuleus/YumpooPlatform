package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery.ConnectTargetProjectSnapshot;
import com.yumpoo.platform.catalog.api.ProjectDeletionQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.ConnectColumn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectColumnCommands.*;
import static com.yumpoo.platform.workitem.application.ConnectColumnModels.*;
import static com.yumpoo.platform.workitem.application.ConnectionAccess.*;

@Service
public class ConnectColumnService {
    private final ConnectColumnRepository columns;
    private final WorkItemConnectionRepository connections;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectConnectionTargetQuery targets;
    private final ProjectFactWriteGuard guard;
    private final ProjectDeletionQuery deletion;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper json;
    private final Clock clock;

    public ConnectColumnService(ConnectColumnRepository columns, WorkItemConnectionRepository connections,
            ProjectAccessSnapshotQuery access, ProjectConnectionTargetQuery targets, ProjectFactWriteGuard guard,
            ProjectDeletionQuery deletion, IdempotentCommandExecutor idempotency,
            TransactionalEventPort events, ObjectMapper json, Clock clock) {
        this.columns = columns;
        this.connections = connections;
        this.access = access;
        this.targets = targets;
        this.guard = guard;
        this.deletion = deletion;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Catalog catalog(CurrentActor actor, UUID projectId) {
        ProjectAccessSnapshot project = visible(actor, projectId);
        List<ConnectColumn> items = columns.findActive(actor.companyId(), projectId);
        var incoming = columns.findIncoming(actor.companyId(), projectId);
        Set<UUID> ids = new LinkedHashSet<>();
        items.forEach(column -> ids.addAll(column.targetProjectIds()));
        incoming.forEach(column -> ids.add(column.projectId()));
        var snapshots = targets.findByIds(actor.companyId(), ids);
        var visibility = access.findVisible(actor, ids);
        var incomingViews = incoming.stream().map(column -> {
            var source = snapshots.get(column.projectId());
            boolean available = available(source, visibility.get(column.projectId()));
            return new IncomingColumn(column.columnId(), available ? column.columnName() : "不可访问的连接列", column.projectId(),
                    available ? source.code() : "—", available ? source.name() : "不可访问的项目",
                    lifecycle(source), available && writable(project) && writable(visibility.get(column.projectId())), available);
        }).toList();
        return new Catalog(items.stream().map(column -> view(column, project, snapshots, visibility)).toList(),
                !incomingViews.isEmpty(), incomingViews, writable(project), writable(project)
                && project.actorAccess() == ProjectAccessSnapshot.ActorProjectAccess.OWNER);
    }

    @Transactional(readOnly = true)
    public void requireVisibleColumn(CurrentActor actor, UUID projectId, UUID columnId) {
        visible(actor, projectId);
        columns.find(actor.companyId(), projectId, columnId).orElseThrow(ConnectionAccess::missing);
    }

    public IdempotencyExecutionResult create(Create command) {
        ProjectAccessSnapshot project = visible(command.actor(), command.projectId());
        requireWritable(project);
        String name = name(command.name());
        List<UUID> targetIds = targetIds(command.projectId(), command.targetProjectIds());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(command.actor().userId(),
                "POST", "createConnectColumn", command.idempotencyKey()), command.requestHash()), () -> {
            lockRequestedProjects(command.actor().companyId(), command.projectId(), targetIds);
            var locked = guard.lockForFactWrite(command.actor(), command.projectId());
            requireWritable(locked);
            Instant now = clock.instant();
            columns.lockCatalog(locked.companyId(), locked.projectId(), now);
            if (columns.countActive(locked.companyId(), locked.projectId()) >= ConnectColumn.MAX_COLUMNS)
                throw conflict("CONNECT_COLUMN_LIMIT");
            requireUniqueName(locked.companyId(), locked.projectId(), name, null);
            validateTargets(locked.companyId(), targetIds, List.of());
            var column = ConnectColumn.create(UUID.randomUUID(), locked.companyId(), locked.projectId(),
                    name, targetIds, command.actor().userId(), now);
            if (!columns.insert(column)) throw invalid("name", "DUPLICATE", "连接列名称已存在");
            columns.replaceTargets(column);
            columns.bumpCatalog(locked.companyId(), locked.projectId(), now);
            append("workitem.connect_column_created", column, command.actor(), Map.of("targetProjectIds", targetIds));
            return stored(201, response(command.actor(), column), column.id(), column.rowVersion());
        });
    }

    @Transactional
    public Column update(Update command) {
        visible(command.actor(), command.projectId());
        var targetIds = targetIds(command.projectId(), command.targetProjectIds());
        lockRequestedProjects(command.actor().companyId(), command.projectId(), targetIds);
        var project = guard.lockForFactWrite(command.actor(), command.projectId());
        requireWritable(project);
        Instant now = clock.instant();
        columns.lockCatalog(project.companyId(), project.projectId(), now);
        var before = columns.lock(project.companyId(), project.projectId(), command.columnId(), false)
                .filter(ConnectColumn::active).orElseThrow(ConnectionAccess::missing);
        requireVersion(before.rowVersion(), command.expectedVersion());
        String name = name(command.name());
        if (before.name().equals(name) && before.targetProjectIds().equals(targetIds)) return response(command.actor(), before);
        requireUniqueName(project.companyId(), project.projectId(), name, before.id());
        validateTargets(project.companyId(), targetIds, before.targetProjectIds());
        List<UUID> added = targetIds.stream().filter(id -> !before.targetProjectIds().contains(id)).toList();
        List<UUID> removed = before.targetProjectIds().stream().filter(id -> !targetIds.contains(id)).toList();
        for (UUID id : removed) {
            long count = connections.countActiveTarget(project.companyId(), before.id(), id);
            if (count > 0) throw ApplicationException.withSafeDetails(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "CONNECT_TARGET_IN_USE", Map.of("targetProjectId", id, "activeConnectionCount", count));
        }
        var after = before.update(name, targetIds, command.actor().userId(), now);
        if (!columns.update(after, before.rowVersion())) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        columns.replaceTargets(after);
        columns.bumpCatalog(project.companyId(), project.projectId(), now);
        List<String> changed = new ArrayList<>();
        if (!before.name().equals(name)) changed.add("name");
        if (!before.targetProjectIds().equals(targetIds)) changed.add("targetProjectIds");
        append("workitem.connect_column_updated", after, command.actor(), Map.of("changedFields", changed,
                "addedTargetProjectIds", added, "removedTargetProjectIds", removed));
        return response(command.actor(), after);
    }

    public IdempotencyExecutionResult delete(Delete command) {
        visible(command.actor(), command.projectId());
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(command.actor().userId(),
                "DELETE", "deleteConnectColumn", command.idempotencyKey()), command.requestHash()), () -> {
            var project = guard.lockForFactWrite(command.actor(), command.projectId());
            requireWritable(project);
            if (project.actorAccess() != ProjectFactWriteSnapshot.ActorProjectAccess.OWNER)
                throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
            Instant now = clock.instant();
            columns.lockCatalog(project.companyId(), project.projectId(), now);
            var before = columns.lock(project.companyId(), project.projectId(), command.columnId(), false)
                    .filter(ConnectColumn::active).orElseThrow(ConnectionAccess::missing);
            requireVersion(before.rowVersion(), command.expectedVersion());
            var after = before.delete(command.actor().userId(), now);
            if (!columns.update(after, before.rowVersion())) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            long removed = connections.deleteColumnConnections(project.companyId(), after.id(), command.actor().userId(), now);
            columns.bumpCatalog(project.companyId(), project.projectId(), now);
            append("workitem.connect_column_deleted", after, command.actor(), Map.of("removedConnectionCount", removed));
            return stored(200, new DeleteResult(after.id(), removed), after.id(), after.rowVersion());
        });
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        return access.findVisible(actor, projectId).orElseThrow(ConnectionAccess::missing);
    }

    private Column response(CurrentActor actor, ConnectColumn column) {
        return view(column, visible(actor, column.projectId()), targets.findByIds(actor.companyId(), column.targetProjectIds()),
                access.findVisible(actor, column.targetProjectIds()));
    }

    private static Column view(ConnectColumn column, ProjectAccessSnapshot source,
            Map<UUID, ConnectTargetProjectSnapshot> targets, Map<UUID, ProjectAccessSnapshot> visibility) {
        return new Column(column.id(), column.projectId(), column.name(), column.targetProjectIds().stream().map(id -> {
            var target = targets.get(id);
            boolean available = available(target, visibility.get(id));
            return new Target(id, available ? target.code() : "—", available ? target.name() : "不可访问的项目", lifecycle(target),
                    available && writable(source) && writable(visibility.get(id)), available);
        }).toList(), column.rowVersion(), StrongEtag.format(column.rowVersion()), column.createdAt());
    }

    private static boolean available(ConnectTargetProjectSnapshot project, ProjectAccessSnapshot visible) {
        return project != null && !project.purging()
                && (project.lifecycle() == ProjectAccessSnapshot.ProjectLifecycle.ACTIVE || visible != null);
    }
    private static String lifecycle(ConnectTargetProjectSnapshot project) {
        return project==null ? "ARCHIVED" : project.lifecycle().name();
    }

    private void validateTargets(UUID companyId, List<UUID> ids, List<UUID> existing) {
        var snapshots = targets.findByIds(companyId, ids);
        for (UUID id : ids) {
            var target = snapshots.get(id);
            if (target == null || target.purging()) throw missing();
            if (!existing.contains(id) && target.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ACTIVE)
                throw conflict("PROJECT_ARCHIVED");
        }
    }

    private void lockRequestedProjects(UUID companyId, UUID source, List<UUID> targets) {
        for (UUID id : java.util.stream.Stream.concat(java.util.stream.Stream.of(source), targets.stream())
                .distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList())
            deletion.lockForProjection(companyId, id).filter(project -> project.purgeStartedAt() == null)
                    .orElseThrow(ConnectionAccess::missing);
    }

    private void requireUniqueName(UUID companyId, UUID projectId, String name, UUID excludingId) {
        if (columns.nameExists(companyId, projectId, name, excludingId))
            throw invalid("name", "DUPLICATE", "连接列名称已存在");
    }

    private static String name(String name) {
        try { return ConnectColumn.normalizeName(name); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw invalid("name", "INVALID_CONNECT_COLUMN_NAME", "连接列名称无效或使用了保留名称");
        }
    }

    private static List<UUID> targetIds(UUID projectId, List<UUID> ids) {
        try { return ConnectColumn.normalizeTargets(projectId, ids); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw invalid("targetProjectIds", "INVALID_CONNECT_TARGETS", "请选择 1–20 个其他项目");
        }
    }

    private void append(String type, ConnectColumn column, CurrentActor actor, Map<String, Object> changes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", column.projectId());
        payload.put("columnId", column.id());
        payload.put("name", column.name());
        payload.putAll(changes);
        events.append(new EventDraft(type, 1, "ConnectColumn", column.id(), column.rowVersion(), column.companyId(),
                EventActor.user(actor.userId()), json.valueToTree(payload)));
    }

    private StoredCommandResult stored(int status, Object view, UUID id, long version) {
        return new StoredCommandResult(status, json.writeValueAsString(view), id, StrongEtag.format(version));
    }
}
