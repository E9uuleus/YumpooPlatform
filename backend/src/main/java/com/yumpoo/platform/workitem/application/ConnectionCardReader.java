package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery.ConnectTargetProjectSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionRepository.*;

@Service
public class ConnectionCardReader {
    private final WorkItemConnectionRepository connections;
    private final ProjectConnectionTargetQuery projects;
    private final ProjectAccessSnapshotQuery access;
    private final MinimalUserSnapshotQuery users;

    public ConnectionCardReader(WorkItemConnectionRepository connections, ProjectConnectionTargetQuery projects,
            ProjectAccessSnapshotQuery access, MinimalUserSnapshotQuery users) {
        this.connections = connections;
        this.projects = projects;
        this.access = access;
        this.users = users;
    }

    public Map<UUID, ConnectionView> connections(CurrentActor actor, List<ConnectionRow> rows, boolean includingDeleted) {
        if (rows.isEmpty()) return Map.of();
        Set<UUID> itemIds = new LinkedHashSet<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            itemIds.add(row.connection().sourceWorkItemId());
            itemIds.add(row.connection().targetWorkItemId());
            userIds.add(row.connection().createdByUserId());
        });
        Context context = load(actor, itemIds, userIds, includingDeleted);
        Map<UUID, ConnectionView> result = new LinkedHashMap<>();
        for (ConnectionRow row : rows) {
            var connection = row.connection();
            boolean writable = row.columnActive() && connection.active()
                    && active(context.projects().get(connection.sourceProjectId()))
                    && active(context.projects().get(connection.targetProjectId()))
                    && (ConnectionAccess.member(context.access().get(connection.sourceProjectId()))
                        || ConnectionAccess.member(context.access().get(connection.targetProjectId())));
            result.put(connection.id(), new ConnectionView(connection.id(), StrongEtag.format(connection.rowVersion()),
                    connection.rowVersion(), connection.columnId(), row.columnName(), connection.origin().name(),
                    connection.active(), context.cards().get(connection.sourceWorkItemId()),
                    context.cards().get(connection.targetWorkItemId()), connection.createdAt(),
                    person(connection.createdByUserId(), context.people()), new Capabilities(writable)));
        }
        return result;
    }

    public Map<UUID, ConnectionCard> cards(CurrentActor actor, Collection<UUID> itemIds) {
        return load(actor, itemIds, new LinkedHashSet<>(), false).cards();
    }

    private Context load(CurrentActor actor, Collection<UUID> itemIds, Set<UUID> userIds, boolean includingDeleted) {
        var rows = connections.findCards(actor.companyId(), itemIds, includingDeleted);
        Set<UUID> projectIds = new LinkedHashSet<>();
        for (CardRow row : rows) {
            projectIds.add(row.projectId());
            if (row.assigneeUserId() != null) userIds.add(row.assigneeUserId());
        }
        var snapshots = projects.findByIds(actor.companyId(), projectIds);
        var visibility = access.findVisible(actor, projectIds);
        var people = users.findByUserIds(actor.companyId(), userIds);
        Map<UUID, ConnectionCard> cards = new LinkedHashMap<>();
        for (CardRow row : rows) {
            var project = snapshots.get(row.projectId());
            cards.put(row.workItemId(), new ConnectionCard(row.workItemId(), row.itemNo(), row.title(), row.archived(),
                    row.projectId(), project.code(), project.name(), project.lifecycle().name(), row.status(),
                    row.priority(), row.category(), row.assigneeUserId() == null ? null : person(row.assigneeUserId(), people),
                    visibility.containsKey(row.projectId())));
        }
        return new Context(cards, snapshots, visibility, people);
    }

    private static boolean active(ConnectTargetProjectSnapshot project) {
        return project.lifecycle() == ProjectAccessSnapshot.ProjectLifecycle.ACTIVE;
    }

    private static ConnectionCardAssignee person(UUID userId, Map<UUID, MinimalUserSnapshot> people) {
        var person = people.get(userId);
        return new ConnectionCardAssignee(userId, person == null ? "历史成员" : person.displayName());
    }

    private record Context(Map<UUID, ConnectionCard> cards, Map<UUID, ConnectTargetProjectSnapshot> projects,
            Map<UUID, ProjectAccessSnapshot> access, Map<UUID, MinimalUserSnapshot> people) {}
}
