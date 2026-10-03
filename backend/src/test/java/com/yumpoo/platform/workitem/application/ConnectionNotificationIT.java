package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.notification.api.NotificationInboxProjection;
import com.yumpoo.platform.notification.application.NotificationInboxQueryService;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionCommands.*;
import static org.assertj.core.api.Assertions.*;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="yumpoo.outbox.enabled=false")
@Transactional
class ConnectionNotificationIT {
    @Autowired JdbcClient jdbc;
    @Autowired WorkItemService items;
    @Autowired ConnectColumnService columns;
    @Autowired WorkItemConnectionService connections;
    @Autowired ContentRepository contents;
    @Autowired WorkItemLabelRepository labels;
    @Autowired ObjectMapper json;
    @Autowired NotificationInboxProjection projection;
    @Autowired NotificationInboxQueryService inbox;

    @Test void realCreateAndLinkNotifiesOnlyTargetOwnerWithoutSourceReferencesAndReauthorizesReads() {
        var fixture=new ConnectionFixture(jdbc,items,columns,contents,labels,json);
        var sourceOwner=fixture.user("来源操作者");
        var targetOwner=fixture.user("目标负责人");
        var nextOwner=fixture.user("目标成员");
        var source=fixture.project(sourceOwner,"来源私有项目");
        var target=fixture.project(targetOwner,"目标项目");
        fixture.member(target.id(),nextOwner);
        var sourceItem=fixture.item(sourceOwner,source,"来源私有标题");
        var column=fixture.column(sourceOwner,source,"私有连接列",target.id());
        try (var ignored=correlation()) {
            connections.createConnected(new CreateConnected(sourceOwner,sourceItem.id(),column.id(),target.id(),
                    "通知的目标事项",null,UUID.randomUUID(),hash()));
        }
        var created=event(sourceOwner.userId());
        assertThat(created.payload().path("origin").asText()).isEqualTo("CREATED");
        projection.consume(created); projection.consume(created);
        UUID targetItem=UUID.fromString(created.payload().path("targetWorkItemId").asText());
        var row=jdbc.sql("SELECT * FROM yumpoo.notification_event WHERE source_event_id=:event")
                .param("event",created.eventId()).query().singleRow();
        assertThat(row.get("target_kind")).isEqualTo("WORK_ITEM");
        assertThat(row.get("project_id")).isEqualTo(target.id());
        assertThat(row.get("work_item_id")).isEqualTo(targetItem);
        assertThat(row.get("update_id")).isNull();
        assertThat(row.get("subject_user_id")).isNull();
        assertThat(row.values().toString()).doesNotContain(source.id().toString(),sourceItem.id().toString(),
                column.id().toString(),created.aggregateId().toString(),"私有连接列","来源私有项目","来源私有标题","通知的目标事项");
        var page=inbox.list(targetOwner,ListState.UNREAD,Group.PROJECT,null,20);
        assertThat(page.items()).singleElement().satisfies(notification->{
            assertThat(notification.reason()).isEqualTo(Reason.CONNECTION_CREATED);
            assertThat(notification.target().title()).isEqualTo("通知的目标事项");
            assertThat(notification.target().projectId()).isEqualTo(target.id());
        });
        assertThat(inbox.counts(targetOwner).project()).isOne();
        assertThat(inbox.counts(sourceOwner).total()).isZero();
        assertThat(inbox.counts(nextOwner).total()).isZero();

        fixture.member(target.id(),sourceOwner);
        var existing=fixture.item(targetOwner,target,"关联已有不通知");
        try (var ignored=correlation()) {
            connections.link(new Link(sourceOwner,sourceItem.id(),column.id(),existing.id(),UUID.randomUUID(),hash()));
        }
        var linked=jdbc.sql("SELECT event_id FROM yumpoo.outbox_event WHERE event_type='workitem.connection_created' AND aggregate_id IN "
                +"(SELECT id FROM yumpoo.work_item_connection WHERE target_work_item_id=:item)")
                .param("item",existing.id()).query(UUID.class).single();
        projection.consume(eventById(linked,sourceOwner.userId()));
        assertThat(inbox.counts(targetOwner).total()).isOne();
        inbox.readAll(targetOwner,java.time.Instant.now().plusSeconds(60),Group.PROJECT);
        assertThat(inbox.counts(targetOwner).total()).isZero();

        jdbc.sql("UPDATE yumpoo.project SET owner_user_id=:owner WHERE id=:project")
                .param("owner",nextOwner.userId()).param("project",target.id()).update();
        jdbc.sql("UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=clock_timestamp(),removed_by_user_id=:actor,"
                +"remove_reason='notification authorization test' WHERE project_id=:project AND user_id=:user")
                .param("actor",nextOwner.userId()).param("project",target.id()).param("user",targetOwner.userId()).update();
        assertThat(inbox.list(targetOwner,ListState.ALL,Group.PROJECT,null,20).items()).singleElement().satisfies(notification->{
            assertThat(notification.target()).isEqualTo(Target.inaccessible(TargetKind.WORK_ITEM));
        });
        assertThatThrownBy(()->items.find(targetOwner,targetItem))
                .isInstanceOf(com.yumpoo.platform.foundation.application.error.ApplicationException.class);
    }

    private DomainEventEnvelope event(UUID actor) {
        UUID id=jdbc.sql("SELECT event_id FROM yumpoo.outbox_event WHERE actor_user_id=:actor AND event_type='workitem.connection_created'")
                .param("actor",actor).query(UUID.class).single();
        return eventById(id,actor);
    }
    private DomainEventEnvelope eventById(UUID id,UUID actor) {
        return jdbc.sql("SELECT * FROM yumpoo.outbox_event WHERE event_id=:id").param("id",id).query((row,n)->
                new DomainEventEnvelope(id,row.getString("event_type"),row.getInt("event_version"),row.getTimestamp("occurred_at").toInstant(),
                        row.getString("aggregate_type"),row.getObject("aggregate_id",UUID.class),row.getLong("aggregate_version"),COMPANY,
                        EventActor.user(actor),row.getString("request_id"),row.getString("correlation_id"),null,
                        json.readTree(row.getString("payload_json")))).single();
    }
}
