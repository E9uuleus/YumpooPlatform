package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.Content;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ConnectionFixture {
    static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    record Project(UUID id, UUID contentId, String code) {}
    final JdbcClient jdbc;
    final WorkItemService items;
    final ConnectColumnService columns;
    final ContentRepository contents;
    final WorkItemLabelRepository labels;
    final ObjectMapper json;
    private final Set<UUID> userIds = new java.util.LinkedHashSet<>();

    ConnectionFixture(JdbcClient jdbc, WorkItemService items, ConnectColumnService columns,
            ContentRepository contents, WorkItemLabelRepository labels, ObjectMapper json) {
        this.jdbc = jdbc;
        this.items = items;
        this.columns = columns;
        this.contents = contents;
        this.labels = labels;
        this.json = json;
    }

    CurrentActor user(String name) {
        UUID id = UUID.randomUUID();
        userIds.add(id);
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user (id, company_id, employment_status, account_status,
                    display_name, directory_synced_at, row_version, created_at, updated_at)
                VALUES (:id, :company, 'ACTIVE', 'ENABLED', :name, now(), 0, now(), now())
                """).param("id", id).param("company", COMPANY).param("name", name).update();
        return new CurrentActor(id, COMPANY, 0, Set.of());
    }

    Project project(CurrentActor owner, String name) {
        UUID id = UUID.randomUUID();
        String code = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    lifecycle, owner_user_id, row_version, created_at, created_by_user_id, updated_at, updated_by_user_id)
                SELECT :id, :company, id, :code, :name, 'ACTIVE', :owner, 0, now(), :owner, now(), :owner
                FROM yumpoo.workspace WHERE company_id=:company AND code='MAIN'
                """).param("id", id).param("company", COMPANY).param("code", code)
                .param("name", name).param("owner", owner.userId()).update();
        member(id, owner);
        Instant now = Instant.now();
        contents.initializeCatalog(COMPANY, id, now);
        UUID first = UUID.randomUUID();
        contents.insertAll(List.of(Content.initial(first, COMPANY, id, "REQUIREMENTS", "需求", "BRIGHT_BLUE", 10,
                owner.userId(), now), Content.initial(UUID.randomUUID(), COMPANY, id, "TASKS", "任务", "BRIGHT_GREEN", 20,
                owner.userId(), now)));
        labels.initialize(COMPANY, id, now);
        return new Project(id, first, code);
    }

    void member(UUID projectId, CurrentActor actor) {
        jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id, status,
                    joined_at, joined_by_user_id, row_version)
                VALUES (:id, :company, :project, :user, 'ACTIVE', now(), :user, 0)
                """).param("id", UUID.randomUUID()).param("company", COMPANY).param("project", projectId)
                .param("user", actor.userId()).update();
    }

    WorkItemModels.WorkItemDetail item(CurrentActor actor, Project project, String title) {
        return item(actor, project, title, null);
    }

    WorkItemModels.WorkItemDetail item(CurrentActor actor, Project project, String title, UUID assigneeId) {
        try (var ignored = correlation()) {
            var result = items.create(new WorkItemCommands.Create(actor, project.id(), project.contentId(), title,
                    null, assigneeId, null, null, null, null, null, UUID.randomUUID(), hash(), DueTimeChange.unchanged()));
            return json.readValue(result.result().responseJson(), WorkItemModels.WorkItemDetail.class);
        }
    }

    ConnectColumnModels.Column column(CurrentActor actor, Project source, String name, UUID... targets) {
        try (var ignored = correlation()) {
            var result = columns.create(new ConnectColumnCommands.Create(actor, source.id(), name, List.of(targets),
                    UUID.randomUUID(), hash()));
            return json.readValue(result.result().responseJson(), ConnectColumnModels.Column.class);
        }
    }

    void archive(Project project) {
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED', archived_at=now(), updated_at=now() WHERE id=:id")
                .param("id", project.id()).update();
    }

    long eventCount(String type) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE company_id=:company AND event_type=:type AND actor_user_id IN (:users)")
                .param("company", COMPANY).param("type", type).param("users", userIds).query(Long.class).single();
    }

    static RequestHash hash() { return new RequestHash("0".repeat(64)); }
    static RequestCorrelationContext.Scope correlation() {
        return RequestCorrelationContext.open(RequestCorrelation.root("up3-" + UUID.randomUUID()));
    }
}
