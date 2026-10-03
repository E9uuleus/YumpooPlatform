package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.audit.api.ActivityProjectionService;
import com.yumpoo.platform.audit.api.WorkItemCellActivityProjectionService;
import com.yumpoo.platform.audit.application.ActivitySummaryRenderer;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionCommands.*;
import static org.assertj.core.api.Assertions.assertThat;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "yumpoo.outbox.enabled=false")
@Transactional
class ConnectionEventProjectionIT {
    private static final Set<String> TYPES = Set.of("workitem.connect_column_created", "workitem.connect_column_updated",
            "workitem.connect_column_deleted", "workitem.connection_created", "workitem.connection_deleted");
    @Autowired private JdbcClient jdbc;
    @Autowired private WorkItemService items;
    @Autowired private ConnectColumnService columns;
    @Autowired private WorkItemConnectionService connections;
    @Autowired private ContentRepository contents;
    @Autowired private WorkItemLabelRepository labels;
    @Autowired private ObjectMapper json;
    @Autowired private ActivityProjectionService projection;
    @Autowired private WorkItemCellActivityProjectionService cellProjection;
    @Autowired private ActivitySummaryRenderer renderer;

    @Test
    void fiveProducedEventTypesMatchClosedPayloadContractsAndKeepTargetProjectionPrivate() throws Exception {
        var fixture = new ConnectionFixture(jdbc, items, columns, contents, labels, json);
        var actor = fixture.user("连接事件操作者");
        var source = fixture.project(actor, "来源项目私有名称");
        var target = fixture.project(actor, "目标项目");
        var sourceItem = fixture.item(actor, source, "来源工作项私有标题");
        var targetItem = fixture.item(actor, target, "目标工作项标题");
        try (var ignored = correlation()) {
            var column = fixture.column(actor, source, "原列名", target.id());
            columns.update(new ConnectColumnCommands.Update(actor, source.id(), column.id(), 0, "研发跟进", List.of(target.id())));
            var linked = connections.link(new Link(actor, sourceItem.id(), column.id(), targetItem.id(), UUID.randomUUID(), hash())).result();
            connections.createConnected(new CreateConnected(actor, sourceItem.id(), column.id(), target.id(), "新建目标", null, UUID.randomUUID(), hash()));
            connections.unlink(new Unlink(actor, linked.resourceId(), 0, UUID.randomUUID(), hash()));
            columns.delete(new ConnectColumnCommands.Delete(actor, source.id(), column.id(), 1, UUID.randomUUID(), hash()));
        }
        var events = jdbc.sql("""
                SELECT * FROM yumpoo.outbox_event WHERE actor_user_id=:actor AND event_type IN (:types)
                ORDER BY occurred_at, event_id
                """).param("actor", actor.userId()).param("types", TYPES).query((row, number) -> new DomainEventEnvelope(
                row.getObject("event_id", UUID.class), row.getString("event_type"), row.getInt("event_version"),
                row.getTimestamp("occurred_at").toInstant(), row.getString("aggregate_type"), row.getObject("aggregate_id", UUID.class),
                row.getLong("aggregate_version"), row.getObject("company_id", UUID.class), EventActor.user(actor.userId()),
                row.getString("request_id"), row.getString("correlation_id"), null, json.readTree(row.getString("payload_json")))).list();
        assertThat(events).hasSize(6);
        assertThat(events.stream().map(DomainEventEnvelope::eventType)).containsAll(TYPES);
        assertThat(projection.subscriptions()).containsAll(TYPES.stream().map(type -> new EventSubscription(type, 1)).toList());
        assertThat(cellProjection.subscriptions()).doesNotContainAnyElementsOf(TYPES.stream().map(type -> new EventSubscription(type, 1)).toList());
        for (var event : events) {
            var schema = json.readTree(Files.readString(Path.of("../contracts/events/schemas/"
                    + event.eventType().replace('_', '-') + "-v1.schema.json")));
            var payloadSchema = schema.path("allOf").get(1).path("properties").path("payload");
            assertThat(event.payload().propertyNames()).containsExactlyInAnyOrderElementsOf(payloadSchema.path("properties").propertyNames());
            assertThat(event.payload().propertyNames()).containsAll(payloadSchema.path("required").valueStream().map(value -> value.asText()).toList());
            assertThat(event.payload().toString()).doesNotContain("来源工作项私有标题", "目标工作项标题", "新建目标", "description", "body");
            projection.consume(event);
            projection.consume(event);
            var projected = jdbc.sql("SELECT * FROM yumpoo.activity_event WHERE event_id=:event ORDER BY scope_id")
                    .param("event", event.eventId()).query().listOfRows();
            boolean columnEvent = event.eventType().startsWith("workitem.connect_column_");
            assertThat(projected).hasSize(columnEvent ? 1 : 2);
            for (var row : projected) {
                assertThat(row.get("scope_type")).isEqualTo("PROJECT");
                var parameters = json.readTree(row.get("safe_parameters").toString());
                String template = row.get("template_code").toString();
                String summary = renderer.render(template, parameters);
                if (columnEvent) {
                    assertThat(row.get("entity_type")).isEqualTo("CONNECT_COLUMN");
                    assertThat(row.get("scope_id")).isEqualTo(source.id());
                    assertThat(summary).contains("连接列「");
                    if (event.eventType().endsWith("_deleted")) {
                        assertThat(event.payload().path("removedConnectionCount").asLong()).isEqualTo(1);
                        assertThat(summary).isEqualTo("删除了连接列「研发跟进」，解除了 1 个连接");
                    }
                } else if (row.get("scope_id").equals(target.id())) {
                    assertThat(row.get("entity_type")).isEqualTo("WORK_ITEM");
                    assertThat(row.get("entity_id").toString()).isEqualTo(event.payload().path("targetWorkItemId").asText());
                    assertThat(row.get("primary_work_item_id")).isEqualTo(row.get("entity_id"));
                    assertThat(row.get("secondary_work_item_id")).isNull();
                    assertThat(row.get("entity_ref")).isNull();
                    assertThat(parameters.isEmpty()).isTrue();
                    assertThat(row.values().toString()).doesNotContain(source.id().toString(), sourceItem.id().toString(), "研发跟进", "来源项目私有名称");
                    if (event.eventType().endsWith("_deleted")) {
                        assertThat(event.payload().path("deleteReason").asText()).isEqualTo("UNLINKED");
                        assertThat(summary).isEqualTo("解除了来自其他项目的连接");
                    } else if (event.payload().path("origin").asText().equals("CREATED")) {
                        assertThat(summary).isEqualTo("从其他项目新建并连接了此工作项");
                    } else assertThat(summary).isEqualTo("从其他项目连接了此工作项");
                } else {
                    assertThat(row.get("scope_id")).isEqualTo(source.id());
                    assertThat(row.get("entity_id")).isEqualTo(sourceItem.id());
                    assertThat(parameters.propertyNames()).containsExactly("columnName");
                    assertThat(summary).isEqualTo(event.eventType().endsWith("_deleted")
                            ? "在「研发跟进」中解除了一个连接" : "在「研发跟进」中连接了其他项目的工作项");
                }
            }
        }
    }
}
