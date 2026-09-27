package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.workitem.application.WorkItemLabelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false")
class NotificationHttpIT {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    private static final UUID PROJECT_ID = UUID.fromString("2a000000-0000-4000-8000-000000000591");
    private static final String SESSION_COOKIE = "__Host-yumpoo-session";
    private static final String CSRF_COOKIE = "__Host-yumpoo-csrf";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @LocalServerPort private int port;
    @Autowired private IdentityAcceptanceFixtureProvisioner provisioner;
    @Autowired private SessionService sessions;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WorkItemLabelRepository labels;

    @Autowired private com.yumpoo.platform.notification.application.NotificationRepository notifications;
    @Autowired private com.yumpoo.platform.foundation.application.outbox.OutboxDispatcher dispatcher;
    @Autowired private com.yumpoo.platform.foundation.application.event.TransactionalEventPort events;
    @Autowired private com.yumpoo.platform.notification.application.NotificationInboxQueryService inbox;
    private ActorFixture owner;
    private ActorFixture member;
    private UUID requirementsId;
    private UUID tasksId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("work-item-category-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("notification-owner", "Work Category Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("notification-member", "Work Category Member");
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            createProject(owner.userId(), member.userId());
            requirementsId = createContent("需求", "BRIGHT_BLUE");
            tasksId = createContent("任务", "BRIGHT_GREEN");
        }
    }

    @AfterEach
    void tearDown() { cleanUp(); }

    @Test void publishedCommentProjectsCurrentExcerptAndDeletionHidesContent() throws Exception {
        var item=created(mutate("POST","/api/v1/projects/"+PROJECT_ID+"/work-items",member,
                workItemBody(tasksId,"通知目标"),null,UUID.randomUUID()));
        String itemId=item.path("id").asText();
        var comment=created(mutate("POST","/api/v1/work-items/"+itemId+"/updates",owner,
                "{\"bodyHtml\":\"<p>通知摘要 文本</p>\"}",null,UUID.randomUUID()));
        dispatcher.dispatchOnce();
        var page=ok(get("/api/v1/me/notifications",member));
        assertThat(page.path("items").size()).isOne();
        var row=page.path("items").get(0);
        assertThat(row.path("reason").asText()).isEqualTo("COMMENT");
        assertThat(row.path("target").path("title").asText()).isEqualTo("通知目标");
        assertThat(row.path("target").path("excerpt").asText()).isEqualTo("通知摘要 文本");
        assertThat(row.path("target").path("updateId").asText()).isEqualTo(comment.path("id").asText());
        ok(mutate("DELETE","/api/v1/work-item-updates/"+comment.path("id").asText(),owner,"{}","\"0\"",null));
        assertThat(ok(get("/api/v1/me/notifications",member)).path("items").get(0).path("target").path("accessible").asBoolean()).isFalse();
    }

    @Test void markingArchivedNotificationReadPreservesArchiveAndReadTimestampOnRetry() throws Exception {
        UUID id=seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.MENTION,member.userId());
        String path="/api/v1/me/notifications/"+id;
        ok(mutate("POST",path+"/archive",member,"",null,null));
        var archivedAt=jdbc.sql("SELECT archived_at FROM yumpoo.user_notification WHERE id=:id")
                .param("id",id).query(java.time.OffsetDateTime.class).single();
        assertThat(ok(mutate("POST",path+"/read",member,"",null,null)).path("total").asInt()).isZero();
        var first=ok(get("/api/v1/me/notifications?state=ARCHIVED",member)).path("items").get(0);
        assertThat(first.path("state").asText()).isEqualTo("ARCHIVED");
        assertThat(first.path("readAt").isTextual()).isTrue();
        ok(mutate("POST",path+"/read",member,"",null,null));
        var repeated=ok(get("/api/v1/me/notifications?state=ARCHIVED",member)).path("items").get(0);
        assertThat(repeated.path("readAt")).isEqualTo(first.path("readAt"));
        assertThat(jdbc.sql("SELECT archived_at FROM yumpoo.user_notification WHERE id=:id")
                .param("id",id).query(java.time.OffsetDateTime.class).single()).isEqualTo(archivedAt);
        assertThat(ok(get("/api/v1/me/notifications",member)).path("items").size()).isZero();
    }

    @Test void commentNotifiesBusinessReporterWhenAuditCreatorDiffers() throws Exception {
        var item=created(mutate("POST","/api/v1/projects/"+PROJECT_ID+"/work-items",owner,
                workItemBody(tasksId,"报告人与审计创建人不同"),null,UUID.randomUUID()));
        UUID itemId=UUID.fromString(item.path("id").asText());
        jdbc.sql("UPDATE yumpoo.work_item SET reporter_user_id=:reporter WHERE id=:id")
                .param("reporter",member.userId()).param("id",itemId).update();
        assertThat(jdbc.sql("SELECT created_by_user_id FROM yumpoo.work_item WHERE id=:id")
                .param("id",itemId).query(UUID.class).single()).isEqualTo(owner.userId());
        created(mutate("POST","/api/v1/work-items/"+itemId+"/updates",owner,
                "{\"bodyHtml\":\"<p>应通知报告人</p>\"}",null,UUID.randomUUID()));
        dispatcher.dispatchOnce();
        var page=ok(get("/api/v1/me/notifications",member));
        assertThat(page.path("items").size()).isOne();
        assertThat(page.path("items").get(0).path("reason").asText()).isEqualTo("COMMENT");
        assertThat(ok(get("/api/v1/me/notifications",owner)).path("items").size()).isZero();
    }

    @Test void pendingReplyDoesNotNotifyDeletedParentAuthor() throws Exception {
        var item=created(mutate("POST","/api/v1/projects/"+PROJECT_ID+"/work-items",owner,
                workItemBody(tasksId,"父评论已删除"),null,UUID.randomUUID()));
        String path="/api/v1/work-items/"+item.path("id").asText()+"/updates";
        var parent=created(mutate("POST",path,member,"{\"bodyHtml\":\"<p>即将删除的父评论</p>\"}",null,UUID.randomUUID()));
        var reply=created(mutate("POST",path,owner,json.writeValueAsString(java.util.Map.of(
                "bodyHtml","<p>尚待消费的回复</p>","parentUpdateId",parent.path("id").asText())),null,UUID.randomUUID()));
        jdbc.sql("""
                UPDATE yumpoo.work_item_update SET status='DELETED',body_html=NULL,body_text=NULL,
                    deleted_at=clock_timestamp(),deleted_by_user_id=:user,delete_reason='SELF'
                WHERE id=:id
                """).param("user",member.userId()).param("id",UUID.fromString(parent.path("id").asText())).update();
        assertThat(jdbc.sql("SELECT status FROM yumpoo.work_item_update WHERE id=:id")
                .param("id",UUID.fromString(reply.path("id").asText())).query(String.class).single()).isEqualTo("PUBLISHED");
        dispatcher.dispatchOnce();
        assertThat(ok(get("/api/v1/me/notifications",member)).path("items").size()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id AND status='COMPLETED'")
                .param("id",UUID.fromString(reply.path("id").asText())).query(Long.class).single()).isOne();
    }

    private UUID seed(com.yumpoo.platform.notification.application.NotificationModels.Reason reason, UUID recipient) {
        var e=new com.yumpoo.platform.notification.application.NotificationRepository.Event(UUID.randomUUID(),COMPANY_ID,
                UUID.randomUUID(),"catalog.project_member_added",1,com.yumpoo.platform.notification.application.NotificationModels.TargetKind.PROJECT,PROJECT_ID,
                null,null,recipient,owner.userId(),java.time.Instant.now());
        notifications.append(e,java.util.Map.of(recipient,reason));
        return jdbc.sql("SELECT id FROM yumpoo.user_notification WHERE notification_event_id=:event")
                .param("event",e.id()).query(UUID.class).single();
    }
    @Test void cursorFiltersCountsAndWatermarkDoNotReadLaterNotifications() throws Exception {
        seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.MENTION,member.userId());
        seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.REPLY,member.userId());
        seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.ASSIGNED,member.userId());
        var first=ok(get("/api/v1/me/notifications?limit=1",member));
        assertThat(first.path("items").size()).isOne();
        String cursor=first.path("nextCursor").asText();
        var second=ok(get("/api/v1/me/notifications?limit=1&cursor="+cursor,member));
        assertThat(second.path("items").get(0).path("id")).isNotEqualTo(first.path("items").get(0).path("id"));
        assertThat(get("/api/v1/me/notifications?state=UNREAD&cursor="+cursor,member).statusCode()).isEqualTo(422);
        assertThat(ok(get("/api/v1/me/notifications?group=COMMENT",member)).path("items").size()).isOne();
        var counts=ok(get("/api/v1/me/notifications/unread-count",member));
        assertThat(counts.path("total").asInt()).isEqualTo(3);
        assertThat(counts.path("comment").asInt()).isOne();
        seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.PROJECT_MEMBER_ADDED,member.userId());
        var updated=ok(mutate("POST","/api/v1/me/notifications/read-all",member,
                json.writeValueAsString(java.util.Map.of("upTo",counts.path("serverNow").asText())),null,null));
        assertThat(updated.path("total").asInt()).isOne();
        assertThat(updated.path("project").asInt()).isOne();
    }
    @Test void personalStateMutationsRequireCsrfAndHideOtherUsersRows() throws Exception {
        UUID id=seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.MENTION,member.userId());
        String path="/api/v1/me/notifications/"+id;
        assertThat(mutate("POST",path+"/read",owner,"",null,null).statusCode()).isEqualTo(404);
        var noCsrf=HttpRequest.newBuilder(uri(path+"/read")).header("Cookie",cookies(member)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(noCsrf,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(ok(mutate("POST",path+"/read",member,"",null,null)).path("total").asInt()).isZero();
        assertThat(ok(mutate("POST",path+"/read",member,"",null,null)).path("total").asInt()).isZero();
        assertThat(ok(mutate("POST",path+"/unread",member,"",null,null)).path("total").asInt()).isOne();
        assertThat(ok(mutate("POST",path+"/archive",member,"",null,null)).path("total").asInt()).isZero();
        assertThat(ok(get("/api/v1/me/notifications",member)).path("items").size()).isZero();
        assertThat(ok(get("/api/v1/me/notifications?state=ARCHIVED",member)).path("items").size()).isOne();
        assertThat(get("/api/v1/me/notifications",member).headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void projectRemovalHidesAllReferencesButEffectiveAdminsKeepVisibility() throws Exception {
        seed(com.yumpoo.platform.notification.application.NotificationModels.Reason.PROJECT_MEMBER_REMOVED,member.userId());
        assertThat(ok(get("/api/v1/me/notifications",member)).path("items").get(0).path("target").path("accessible").asBoolean()).isTrue();
        jdbc.sql("DELETE FROM yumpoo.project_membership WHERE project_id=:project AND user_id=:user")
                .param("project",PROJECT_ID).param("user",member.userId()).update();
        var target=ok(get("/api/v1/me/notifications",member)).path("items").get(0).path("target");
        assertThat(target.path("accessible").asBoolean()).isFalse();
        assertThat(target.path("projectId").isNull()).isTrue();
        assertThat(target.path("projectName").isNull()).isTrue();
        for(var role:com.yumpoo.platform.identityaccess.api.PlatformRoleCode.values()) {
            var admin=new com.yumpoo.platform.identityaccess.api.CurrentActor(member.userId(),COMPANY_ID,0,java.util.Set.of(role));
            assertThat(inbox.list(admin,null,null,null,20).items().getFirst().target().accessible()).isTrue();
        }
    }
    @Test void deletedTargetIsCompletedByDispatcherAndReplayDoesNotDuplicateNotifications() throws Exception {
        var item=created(mutate("POST","/api/v1/projects/"+PROJECT_ID+"/work-items",owner,
                workItemBody(tasksId,"删除后不重试"),null,UUID.randomUUID()));
        UUID itemId=UUID.fromString(item.path("id").asText());
        var p=json.createObjectNode().put("projectId",PROJECT_ID.toString()).put("workItemId",itemId.toString())
                .put("contentId",tasksId.toString()).put("itemNo",item.path("itemNo").asText()).put("rowVersion",1)
                .putNull("previousAssigneeUserId").put("assigneeUserId",member.userId().toString());
        com.yumpoo.platform.foundation.application.event.DomainEventEnvelope event;
        try(var ignored=RequestCorrelationContext.open(RequestCorrelation.root("inbox-dispatch-"+UUID.randomUUID()))) {
            event=new TransactionTemplate(transactionManager).execute(tx->events.append(new com.yumpoo.platform.foundation.application.event.EventDraft(
                    "workitem.work_item_assigned",1,"WorkItem",UUID.randomUUID(),1,COMPANY_ID,
                    com.yumpoo.platform.foundation.application.event.EventActor.user(owner.userId()),p)));
        }
        jdbc.sql("DELETE FROM yumpoo.work_item WHERE id=:id").param("id",itemId).update();
        dispatcher.dispatchOnce();
        assertThat(jdbc.sql("SELECT status FROM yumpoo.outbox_event WHERE event_id=:id").param("id",event.eventId()).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(notifications.counts(COMPANY_ID,member.userId()).total()).isZero();
        var e=new com.yumpoo.platform.notification.application.NotificationRepository.Event(UUID.randomUUID(),COMPANY_ID,
                UUID.randomUUID(),"catalog.project_member_added",1,com.yumpoo.platform.notification.application.NotificationModels.TargetKind.PROJECT,PROJECT_ID,null,null,
                member.userId(),owner.userId(),java.time.Instant.now());
        notifications.append(e,java.util.Map.of(member.userId(),com.yumpoo.platform.notification.application.NotificationModels.Reason.PROJECT_MEMBER_ADDED));
        notifications.append(e,java.util.Map.of(member.userId(),com.yumpoo.platform.notification.application.NotificationModels.Reason.PROJECT_MEMBER_ADDED));
        assertThat(notifications.counts(COMPANY_ID,member.userId()).total()).isOne();
    }
    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode created(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private UUID createContent(String name, String color) throws Exception {
        HttpResponse<String> response = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents", owner,
                json.writeValueAsString(java.util.Map.of("name", name, "colorToken", color)), null, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).path("id").asText());
    }

    private String workItemBody(UUID contentId, String title) throws Exception {
        var body = json.createObjectNode();
        if (contentId == null) body.putNull("contentId"); else body.put("contentId", contentId.toString());
        body.put("title", title); body.putNull("priority"); body.putNull("assigneeUserId");
        body.putNull("description"); body.putNull("notes"); body.putNull("timelineStartDate");
        body.putNull("timelineEndDate"); body.putNull("dueDate");
        return json.writeValueAsString(body);
    }

    private String subitemBody(UUID contentId, String title) throws Exception { return workItemBody(contentId, title); }
    private ActorFixture actor(UUID userId) { return new ActorFixture(userId, sessions.issueWebSession(userId, "work-item-category-http")); }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (actor != null) request.header("Cookie", cookies(actor));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mutate(String method, String path, ActorFixture actor, String body,
            String ifMatch, UUID idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Cookie", cookies(actor))
                .header(CSRF_HEADER, actor.session().csrfCredential().value());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
        if (!body.isEmpty()) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private void createProject(UUID ownerId, UUID memberId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id,company_id,workspace_id,project_code,name,project_type,
                    lifecycle,owner_user_id,template_key,template_version,row_version,created_at,
                    created_by_user_id,updated_at,updated_by_user_id,activated_at)
                VALUES (:id,:companyId,:workspaceId,'CATEGORY_WORK','Category Work','PRODUCT_DEVELOPMENT',
                    'ACTIVE',:ownerId,'RND',1,0,transaction_timestamp(),:ownerId,
                    transaction_timestamp(),:ownerId,transaction_timestamp())
                """).param("id", PROJECT_ID).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("ownerId", ownerId).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id,company_id,project_id,user_id,status,joined_at,joined_by_user_id,row_version)
                VALUES (:ownerMembership,:companyId,:projectId,:ownerId,'ACTIVE',transaction_timestamp(),:ownerId,0),
                       (:memberMembership,:companyId,:projectId,:memberId,'ACTIVE',transaction_timestamp(),:ownerId,0)
                """).param("ownerMembership", UUID.randomUUID()).param("memberMembership", UUID.randomUUID())
                    .param("companyId", COMPANY_ID).param("projectId", PROJECT_ID)
                    .param("ownerId", ownerId).param("memberId", memberId).update();
            jdbc.sql("INSERT INTO yumpoo.content_catalog_version (project_id,company_id) VALUES (:projectId,:companyId)")
                    .param("projectId", PROJECT_ID).param("companyId", COMPANY_ID).update();
            labels.initialize(COMPANY_ID, PROJECT_ID, "RND", 1, clock.instant());
        });
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.notification_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.personal_dashboard WHERE company_id=:id").param("id", COMPANY_ID).update();
        for (String table : java.util.List.of("work_item_time_session", "work_item_timer_state", "work_item_time_revision"))
            jdbc.sql("DELETE FROM yumpoo." + table + " WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_update_mention WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_update WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_relation WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_project_counter WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content_catalog_version WHERE company_id=:id")
                .param("id", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:id").param("id", COMPANY_ID).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:id").param("id", COMPANY_ID).update();
        });
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (SELECT id FROM yumpoo.identity_user WHERE company_id=:id)").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:id").param("id", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
