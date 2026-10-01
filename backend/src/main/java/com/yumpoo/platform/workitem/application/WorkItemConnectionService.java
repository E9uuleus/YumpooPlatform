package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.ConnectColumn;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemConnection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectionAccess.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionCommands.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionRepository.ConnectionRow;

@Service
public class WorkItemConnectionService {
    private final WorkItemConnectionRepository connections;
    private final ConnectColumnRepository columns;
    private final WorkItemRepository items;
    private final WorkItemService itemService;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectConnectionTargetQuery targets;
    private final ProjectFactWriteGuard guard;
    private final ConnectionCardReader cards;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkItemConnectionService(WorkItemConnectionRepository connections, ConnectColumnRepository columns,
            WorkItemRepository items, WorkItemService itemService, ProjectAccessSnapshotQuery access,
            ProjectConnectionTargetQuery targets, ProjectFactWriteGuard guard, ConnectionCardReader cards,
            IdempotentCommandExecutor idempotency, TransactionalEventPort events, ObjectMapper json, Clock clock) {
        this.connections = connections;
        this.columns = columns;
        this.items = items;
        this.itemService = itemService;
        this.access = access;
        this.targets = targets;
        this.guard = guard;
        this.cards = cards;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CellList cells(CurrentActor actor, UUID projectId, List<UUID> workItemIds) {
        visible(actor, projectId);
        if (workItemIds == null || workItemIds.isEmpty() || workItemIds.size() > 100
                || workItemIds.stream().anyMatch(java.util.Objects::isNull))
            throw invalid("workItemIds", "INVALID_CONNECTION_ITEM_IDS", "请选择 1–100 个工作项");
        var ids = connections.findActiveProjectItems(actor.companyId(), projectId, workItemIds);
        var outgoing = connections.findOutgoing(actor.companyId(), projectId, ids);
        var incoming = connections.findIncomingCells(actor.companyId(), projectId, ids);
        List<ConnectionRow> rows = new ArrayList<>(outgoing);
        rows.addAll(incoming);
        var views = cards.connections(actor, rows, false);
        Map<UUID, Map<UUID, List<ConnectionView>>> out = new LinkedHashMap<>();
        Map<UUID, List<ConnectionView>> in = new LinkedHashMap<>();
        Map<UUID, Long> totals = new LinkedHashMap<>();
        for (var row : outgoing) out.computeIfAbsent(row.connection().sourceWorkItemId(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(row.connection().columnId(), ignored -> new ArrayList<>()).add(views.get(row.connection().id()));
        for (var row : incoming) {
            UUID id = row.connection().targetWorkItemId();
            in.computeIfAbsent(id, ignored -> new ArrayList<>()).add(views.get(row.connection().id()));
            totals.put(id, row.incomingTotal());
        }
        return new CellList(ids.stream().map(id -> new Cell(id,
                out.getOrDefault(id, Map.of()).entrySet().stream().map(entry -> new Outgoing(entry.getKey(), entry.getValue())).toList(),
                in.getOrDefault(id, List.of()), totals.getOrDefault(id, 0L))).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConnectionPage incoming(CurrentActor actor, UUID workItemId, OffsetPageRequest page) {
        requireActor(actor);
        requirePage(page, 50);
        var item = items.findLocator(actor.companyId(), workItemId).orElseThrow(ConnectionAccess::missing);
        Set<UUID> projectIds = new LinkedHashSet<>(connections.findIncomingProjectIds(actor.companyId(), workItemId));
        projectIds.add(item.projectId());
        var visible = access.findVisible(actor, projectIds);
        if (visible.isEmpty()) throw missing();
        var rows = connections.findIncomingPage(actor.companyId(), workItemId, visible.keySet(), page);
        long total = connections.countIncoming(actor.companyId(), workItemId, visible.keySet());
        var views = cards.connections(actor, rows, false);
        return new ConnectionPage(rows.stream().map(row -> views.get(row.connection().id())).toList(),
                page.page(), page.size(), total, totalPages(total, page.size()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConnectionView find(CurrentActor actor, UUID connectionId) {
        ConnectionRow row = requireVisibleConnection(actor, connectionId);
        var visibility = access.findVisible(actor, endpoints(row.connection()));
        var readable = connections.findReadable(actor.companyId(), connectionId, visibility.keySet())
                .orElseThrow(ConnectionAccess::missing);
        return cards.connections(actor, List.of(readable), false).get(connectionId);
    }

    @Transactional(readOnly = true)
    public ConnectionRow requireVisibleConnection(CurrentActor actor, UUID connectionId) {
        requireActor(actor);
        var row = connections.find(actor.companyId(), connectionId).orElseThrow(ConnectionAccess::missing);
        if (access.findVisible(actor, endpoints(row.connection())).isEmpty()) throw missing();
        return row;
    }

    @Transactional(readOnly = true)
    public CreateOptions createOptions(CurrentActor actor, UUID projectId, UUID columnId, UUID targetProjectId) {
        var source = visible(actor, projectId);
        if (!member(source)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        var column = column(actor.companyId(), projectId, columnId, false);
        requireTarget(column, targetProjectId);
        var target = targets.findByIds(actor.companyId(), List.of(targetProjectId)).get(targetProjectId);
        if (target == null) throw missing();
        if (target.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ACTIVE) throw conflict("PROJECT_ARCHIVED");
        var categories = connections.findActiveCategories(actor.companyId(), targetProjectId);
        if (categories.isEmpty()) throw conflict("PROJECT_CONTENT_UNAVAILABLE");
        return new CreateOptions(targetProjectId, target.name(), categories, categories.getFirst().id());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CandidatePage candidates(CurrentActor actor, UUID projectId, UUID columnId, UUID targetProjectId,
            UUID sourceWorkItemId, String query, OffsetPageRequest page) {
        requireWritable(visible(actor, projectId));
        requirePage(page, 20);
        var column = column(actor.companyId(), projectId, columnId, false);
        requireTarget(column, targetProjectId);
        requireWritable(access.findVisible(actor, targetProjectId).orElse(null));
        items.findLocator(actor.companyId(), projectId, sourceWorkItemId).orElseThrow(ConnectionAccess::missing);
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty() || normalized.length() > 80) throw invalid("q", "INVALID_LENGTH", "搜索词须为 1–80 个字符");
        var rows = connections.findCandidates(actor.companyId(), targetProjectId, columnId, sourceWorkItemId, normalized, page);
        long total = connections.countCandidates(actor.companyId(), targetProjectId, normalized);
        var cardViews = cards.cards(actor, rows.stream().map(WorkItemConnectionRepository.CandidateRow::workItemId).toList());
        return new CandidatePage(rows.stream().map(row -> new Candidate(cardViews.get(row.workItemId()), row.parent(), row.alreadyConnected()))
                .toList(), page.page(), page.size(), total, totalPages(total, page.size()));
    }

    public IdempotencyExecutionResult link(Link command) {
        var source = source(command.actor(), command.workItemId());
        var column = column(command.actor().companyId(), source.projectId(), command.columnId(), false);
        var target = items.findLocator(command.actor().companyId(), command.targetWorkItemId()).orElseThrow(ConnectionAccess::missing);
        requireTarget(column, target.projectId());
        requireWritable(access.findVisible(command.actor(), target.projectId()).orElse(null));
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(command.actor().userId(), "POST",
                "linkWorkItemConnection", command.idempotencyKey()), command.requestHash()), () -> {
            lockProjects(command.actor(), source.projectId(), target.projectId(), Set.of(source.projectId(), target.projectId()));
            var lockedColumn = column(command.actor().companyId(), source.projectId(), command.columnId(), true);
            requireTarget(lockedColumn, target.projectId());
            var lockedItems = lockItems(command.actor().companyId(), Map.of(source.workItemId(), source.projectId(),
                    target.workItemId(), target.projectId()));
            lockedItems.values().forEach(WorkItemConnectionService::requireUnarchived);
            var existing = connections.findActivePair(command.actor().companyId(), lockedColumn.id(),
                    source.workItemId(), target.workItemId());
            if (existing.isPresent()) return stored(200, response(command.actor(), existing.get(), lockedColumn));
            requireCapacity(command.actor().companyId(), lockedColumn.id(), source.workItemId());
            var connection = WorkItemConnection.create(UUID.randomUUID(), command.actor().companyId(), lockedColumn.id(),
                    source.projectId(), source.workItemId(), target.projectId(), target.workItemId(),
                    WorkItemConnection.Origin.LINKED, command.actor().userId(), clock.instant());
            if (!connections.insert(connection)) {
                var duplicate = connections.findActivePair(command.actor().companyId(), lockedColumn.id(),
                        source.workItemId(), target.workItemId()).orElseThrow(() -> new IllegalStateException("connection conflict without active pair"));
                return stored(200, response(command.actor(), duplicate, lockedColumn));
            }
            append("workitem.connection_created", connection, lockedColumn.name(), command.actor());
            return stored(201, response(command.actor(), connection, lockedColumn));
        });
    }

    public IdempotencyExecutionResult createConnected(CreateConnected command) {
        var source = source(command.actor(), command.workItemId());
        var column = column(command.actor().companyId(), source.projectId(), command.columnId(), false);
        requireTarget(column, command.targetProjectId());
        String title = command.title() == null ? "" : command.title().strip();
        if (title.isEmpty() || title.length() > 300) throw invalid("title", "INVALID_LENGTH", "标题须为 1–300 个字符");
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(command.actor().userId(), "POST",
                "createConnectedWorkItem", command.idempotencyKey()), command.requestHash()), () -> {
            var projects = lockProjects(command.actor(), source.projectId(), command.targetProjectId(), Set.of(source.projectId()));
            var lockedColumn = column(command.actor().companyId(), source.projectId(), command.columnId(), true);
            requireTarget(lockedColumn, command.targetProjectId());
            UUID contentId = command.contentId();
            if (contentId == null) {
                var categories = connections.findActiveCategories(command.actor().companyId(), command.targetProjectId());
                if (categories.isEmpty()) throw conflict("PROJECT_CONTENT_UNAVAILABLE");
                contentId = categories.getFirst().id();
            }
            WorkItem target = itemService.createRootItemForConnection(projects.get(command.targetProjectId()), contentId, title, command.actor());
            WorkItem lockedSource = lockItems(command.actor().companyId(), Map.of(source.workItemId(), source.projectId()))
                    .get(source.workItemId());
            requireUnarchived(lockedSource);
            requireCapacity(command.actor().companyId(), lockedColumn.id(), lockedSource.id());
            var connection = WorkItemConnection.create(UUID.randomUUID(), command.actor().companyId(), lockedColumn.id(),
                    source.projectId(), source.workItemId(), target.projectId(), target.id(), WorkItemConnection.Origin.CREATED,
                    command.actor().userId(), clock.instant());
            if (!connections.insert(connection)) throw new IllegalStateException("new connected item already connected");
            append("workitem.connection_created", connection, lockedColumn.name(), command.actor());
            return stored(201, response(command.actor(), connection, lockedColumn));
        });
    }

    public IdempotencyExecutionResult unlink(Unlink command) {
        var snapshot = requireVisibleConnection(command.actor(), command.connectionId()).connection();
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(command.actor().userId(), "DELETE",
                "unlinkWorkItemConnection", command.idempotencyKey()), command.requestHash()), () -> {
            var visibility = access.findVisible(command.actor(), endpoints(snapshot));
            UUID memberProject = endpoints(snapshot).stream().sorted(Comparator.comparing(UUID::toString))
                    .filter(id -> member(visibility.get(id))).findFirst()
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.ACCESS_DENIED));
            lockProjects(command.actor(), snapshot.sourceProjectId(), snapshot.targetProjectId(), Set.of(memberProject));
            var column = column(command.actor().companyId(), snapshot.sourceProjectId(), snapshot.columnId(), true);
            lockItems(command.actor().companyId(), Map.of(snapshot.sourceWorkItemId(), snapshot.sourceProjectId(),
                    snapshot.targetWorkItemId(), snapshot.targetProjectId()));
            var connection = connections.lock(command.actor().companyId(), command.connectionId()).orElseThrow(ConnectionAccess::missing);
            requireVersion(connection.rowVersion(), command.expectedVersion());
            if (!connection.active()) throw conflict("CONNECTION_NOT_ACTIVE");
            var deleted = connection.delete(command.actor().userId(), WorkItemConnection.DeleteReason.UNLINKED, clock.instant());
            if (!connections.softDelete(deleted, connection.rowVersion())) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            append("workitem.connection_deleted", deleted, column.name(), command.actor());
            return stored(200, response(command.actor(), deleted, column));
        });
    }

    private WorkItemModels.WorkItemLocator source(CurrentActor actor, UUID id) {
        requireActor(actor);
        var source = items.findLocator(actor.companyId(), id).orElseThrow(ConnectionAccess::missing);
        requireWritable(visible(actor, source.projectId()));
        return source;
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        return access.findVisible(actor, projectId).orElseThrow(ConnectionAccess::missing);
    }

    private ConnectColumn column(UUID companyId, UUID projectId, UUID columnId, boolean lock) {
        return (lock ? columns.lock(companyId, projectId, columnId, true) : columns.find(companyId, projectId, columnId))
                .filter(ConnectColumn::active).orElseThrow(ConnectionAccess::missing);
    }

    private Map<UUID, ItemWriteTarget> lockProjects(CurrentActor actor, UUID sourceId, UUID targetId, Set<UUID> memberProjects) {
        Map<UUID, ItemWriteTarget> result = new LinkedHashMap<>();
        for (UUID id : List.of(sourceId, targetId).stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
            if (memberProjects.contains(id)) {
                var project = guard.lockForFactWrite(actor, id);
                requireWritable(project);
                result.put(id, ItemWriteTarget.from(project));
            } else {
                var project = targets.lockAsConnectionTarget(actor.companyId(), id);
                result.put(id, new ItemWriteTarget(actor.companyId(), id, project.code()));
            }
        }
        return result;
    }

    private Map<UUID, WorkItem> lockItems(UUID companyId, Map<UUID, UUID> itemProjects) {
        Map<UUID, WorkItem> result = new LinkedHashMap<>();
        for (var entry : itemProjects.entrySet().stream().sorted(Comparator
                .comparing((Map.Entry<UUID, UUID> item) -> item.getValue().toString())
                .thenComparing(item -> item.getKey().toString())).toList()) {
            WorkItem item = items.lockProjectItemIncludingDeleted(companyId, entry.getValue(), entry.getKey())
                    .orElseThrow(ConnectionAccess::missing);
            result.put(entry.getKey(), item);
        }
        return result;
    }

    private void requireCapacity(UUID companyId, UUID columnId, UUID sourceId) {
        if (connections.countActiveCell(companyId, columnId, sourceId) >= WorkItemConnection.MAX_PER_CELL)
            throw conflict("CONNECTION_LIMIT");
    }

    private static void requireTarget(ConnectColumn column, UUID targetId) {
        if (!column.targetProjectIds().contains(targetId))
            throw invalid("targetProjectId", "CONNECT_TARGET_NOT_IN_COLUMN", "该项目不在此连接列中");
    }

    private static void requireUnarchived(WorkItem item) {
        if (item.deleted()) throw missing();
        if (item.archived()) throw conflict("WORK_ITEM_ARCHIVED");
    }

    private static Set<UUID> endpoints(WorkItemConnection connection) {
        return Set.of(connection.sourceProjectId(), connection.targetProjectId());
    }

    private static void requirePage(OffsetPageRequest page, int maximum) {
        if (page.size() > maximum) throw invalid("size", "PAGE_SIZE_OUT_OF_RANGE", "分页大小超出允许范围");
    }

    private static int totalPages(long total, int size) { return (int) Math.ceil((double) total / size); }

    private ConnectionView response(CurrentActor actor, WorkItemConnection connection, ConnectColumn column) {
        return cards.connections(actor, List.of(new ConnectionRow(connection, column.name(), column.active(), 0)), true).get(connection.id());
    }

    private StoredCommandResult stored(int status, ConnectionView view) {
        return new StoredCommandResult(status, json.writeValueAsString(view), view.id(), StrongEtag.format(view.rowVersion()));
    }

    private void append(String type, WorkItemConnection connection, String columnName, CurrentActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectionId", connection.id());
        payload.put("columnId", connection.columnId());
        payload.put("columnName", columnName);
        payload.put("origin", connection.origin().name());
        payload.put("sourceProjectId", connection.sourceProjectId());
        payload.put("sourceWorkItemId", connection.sourceWorkItemId());
        payload.put("targetProjectId", connection.targetProjectId());
        payload.put("targetWorkItemId", connection.targetWorkItemId());
        if (connection.deleteReason() != null) payload.put("deleteReason", connection.deleteReason().name());
        events.append(new EventDraft(type, 1, "WorkItemConnection", connection.id(), connection.rowVersion(), connection.companyId(),
                EventActor.user(actor.userId()), json.valueToTree(payload)));
    }
}
