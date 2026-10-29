package com.yumpoo.platform.workitem.api;

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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false")
class WorkItemImportHttpIT {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    private static final UUID PROJECT_ID = UUID.fromString("2a000000-0000-4000-8000-000000000391");
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

    private ActorFixture owner;
    private ActorFixture member;
    private UUID contentId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("work-item-import-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("work-import-owner", "Work Import Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("work-import-member", "Work Import Member");
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            createProject(owner.userId(), member.userId());
            contentId = createContent("需求", "BRIGHT_BLUE");
        }
    }

    @AfterEach
    void tearDown() { cleanUp(); }

    @Test
    void dryRunWritesNothingAndTheCommitWritesDirectlyWithoutEventsOrNotifications() throws Exception {
        ObjectNode request = request(false, "CREATE", List.of("STATUS", "PARENT", "ASSIGNEES"),
                row(1, "父需求", "DONE", null, member.userId()),
                row(2, "子任务", "IN_PROGRESS", "父需求", null),
                row(3, "孙任务", "NOT_STARTED", "子任务", null),
                row(4, "   ", "NOT_STARTED", null, null));

        JsonNode preview = ok(post(owner, request.deepCopy().put("dryRun", true)));

        assertThat(preview.path("dryRun").asBoolean()).isTrue();
        assertThat(preview.path("createdCount").asInt()).isEqualTo(3);
        assertThat(preview.path("failedCount").asInt()).isEqualTo(1);
        assertThat(preview.path("rows").get(3).path("issues").get(0).path("code").asText())
                .isEqualTo("TITLE_INVALID");
        assertThat(count("work_item")).isZero();
        assertThat(count("work_item_relation")).isZero();
        assertThat(count("security_audit_event WHERE action='WORK_ITEM_IMPORT'")).isZero();

        UUID key = UUID.randomUUID();
        HttpResponse<String> committed = post(owner, request, key);
        JsonNode result = ok(committed);

        assertThat(result.path("createdCount").asInt()).isEqualTo(3);
        assertThat(count("work_item")).isEqualTo(3);
        assertThat(count("work_item_relation WHERE deleted_at IS NULL")).isEqualTo(2);
        assertThat(result.path("rows").get(0).path("itemNo").asText()).isEqualTo("IMPORT_WORK-1");
        assertThat(result.path("rows").get(2).path("issues").get(0).path("code").asText())
                .isEqualTo("PARENT_FLATTENED");
        assertThat(jdbc.sql("SELECT status_code || ':' || (completed_at IS NOT NULL) FROM yumpoo.work_item "
                        + "WHERE title='父需求'").query(String.class).single()).isEqualTo("DONE:false");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_assignee WHERE user_id=:id")
                .param("id", member.userId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT title FROM yumpoo.work_item ORDER BY project_sort_key")
                .query(String.class).list()).containsExactly("父需求", "子任务", "孙任务");
        assertThat(jdbc.sql("SELECT p.title FROM yumpoo.work_item_relation r JOIN yumpoo.work_item p "
                        + "ON p.id=r.left_work_item_id WHERE r.deleted_at IS NULL")
                .query(String.class).list()).containsExactly("父需求", "父需求");
        assertThat(count("outbox_event WHERE event_type LIKE 'workitem.work_item%'")).isZero();
        assertThat(count("outbox_event WHERE event_type LIKE 'notification%'")).isZero();
        assertThat(count("security_audit_event WHERE action='WORK_ITEM_IMPORT'")).isEqualTo(1);

        HttpResponse<String> replay = post(owner, request, key);
        assertThat(replay.body()).isEqualTo(committed.body());
        assertThat(count("work_item")).isEqualTo(3);
    }

    @Test
    void skipThenUpdateModesMatchByTitleAndOnlyOverwriteMappedFields() throws Exception {
        ok(post(owner, request(false, "CREATE", List.of("STATUS", "PARENT"),
                row(1, "父需求", "NOT_STARTED", null, null),
                row(2, "子任务", "NOT_STARTED", "父需求", null))));
        long itemsBefore = count("work_item");

        JsonNode skipped = ok(post(owner, request(false, "SKIP", List.of("STATUS", "PARENT"),
                row(1, "父需求", "NOT_STARTED", null, null),
                row(2, "子任务", "NOT_STARTED", "父需求", null),
                row(3, "新任务", "NOT_STARTED", null, null))));

        assertThat(skipped.path("skippedCount").asInt()).isEqualTo(2);
        assertThat(skipped.path("createdCount").asInt()).isEqualTo(1);
        assertThat(skipped.path("rows").get(0).path("itemNo").asText()).isEqualTo("IMPORT_WORK-1");
        assertThat(count("work_item")).isEqualTo(itemsBefore + 1);

        JsonNode updated = ok(post(owner, request(false, "UPDATE", List.of("STATUS", "PARENT"),
                row(1, "父需求", "IN_PROGRESS", null, null),
                row(2, "子任务", "DONE", null, null))));

        assertThat(updated.path("updatedCount").asInt()).isEqualTo(2);
        assertThat(count("work_item")).isEqualTo(itemsBefore + 1);
        assertThat(jdbc.sql("SELECT status_code FROM yumpoo.work_item WHERE title='父需求'")
                .query(String.class).single()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.sql("SELECT status_code || ':' || (completed_at IS NOT NULL) FROM yumpoo.work_item "
                        + "WHERE title='子任务'").query(String.class).single()).isEqualTo("DONE:false");
        assertThat(count("work_item_relation WHERE deleted_at IS NULL")).isZero();
        assertThat(count("work_item_relation WHERE deleted_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("outbox_event WHERE event_type LIKE 'workitem.work_item%'")).isZero();
    }

    @Test
    void missingIdempotencyKeyAndUnknownProjectAreRejected() throws Exception {
        ObjectNode request = request(true, "CREATE", List.of(), row(1, "任务", "NOT_STARTED", null, null));

        HttpResponse<String> missingKey = rawPost(owner, PROJECT_ID, request, null);
        HttpResponse<String> unknownProject = rawPost(owner, UUID.randomUUID(), request, UUID.randomUUID());

        assertThat(missingKey.statusCode()).isIn(400, 428);
        assertThat(unknownProject.statusCode()).isEqualTo(404);
    }

    @Test
    void archivedRootDoesNotMakeAnOtherwiseValidImportFailAfterPreview() throws Exception {
        ok(post(owner, request(false, "CREATE", List.of("PARENT"),
                row(1, "已归档父项", "NOT_STARTED", null, null),
                row(2, "子项", "NOT_STARTED", "已归档父项", null))));
        jdbc.sql("UPDATE yumpoo.work_item SET archived=true WHERE title='已归档父项'").update();

        for (boolean dryRun : List.of(true, false)) {
            JsonNode result = ok(post(owner, request(dryRun, "CREATE", List.of("PARENT"),
                    row(1, "新项", "NOT_STARTED", "子项", null),
                    row(2, "独立项", "NOT_STARTED", null, null))));
            assertThat(result.path("createdCount").asInt()).isEqualTo(2);
            assertThat(result.path("rows").get(0).path("issues").get(0).path("code").asText())
                    .isEqualTo("PARENT_NOT_FOUND");
        }
        assertThat(count("work_item")).isEqualTo(4);
        assertThat(count("work_item_relation WHERE deleted_at IS NULL")).isEqualTo(1);
    }

    private ObjectNode request(boolean dryRun, String mode, List<String> fields, ObjectNode... rows) {
        ObjectNode body = json.createObjectNode();
        body.put("dryRun", dryRun);
        body.put("duplicateMode", mode);
        ArrayNode fieldNodes = body.putArray("fields");
        fields.forEach(fieldNodes::add);
        ArrayNode rowNodes = body.putArray("rows");
        for (ObjectNode row : rows) rowNodes.add(row);
        return body;
    }

    private ObjectNode row(int number, String title, String status, String parentTitle, UUID assignee) {
        ObjectNode row = json.createObjectNode();
        row.put("rowNumber", number);
        row.put("title", title);
        row.put("contentId", contentId.toString());
        row.put("statusCode", status);
        row.putNull("priority");
        ArrayNode assignees = row.putArray("assigneeUserIds");
        if (assignee != null) assignees.add(assignee.toString());
        row.putNull("dueDate");
        if (parentTitle == null) row.putNull("parentTitle"); else row.put("parentTitle", parentTitle);
        row.putNull("notes");
        return row;
    }

    private long count(String tableAndFilter) {
        return jdbc.sql("SELECT count(*) FROM yumpoo." + tableAndFilter).query(Long.class).single();
    }

    private HttpResponse<String> post(ActorFixture actor, ObjectNode body) throws Exception {
        return post(actor, body, UUID.randomUUID());
    }

    private HttpResponse<String> post(ActorFixture actor, ObjectNode body, UUID key) throws Exception {
        return rawPost(actor, PROJECT_ID, body, key);
    }

    private HttpResponse<String> rawPost(ActorFixture actor, UUID projectId, ObjectNode body, UUID key)
            throws Exception {
        return mutate("POST", "/api/v1/projects/" + projectId + "/work-item-imports", actor,
                json.writeValueAsString(body), null, key);
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private UUID createContent(String name, String color) throws Exception {
        HttpResponse<String> response = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents", owner,
                json.writeValueAsString(java.util.Map.of("name", name, "colorToken", color)), null, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).path("id").asText());
    }

    private ActorFixture actor(UUID userId) { return new ActorFixture(userId, sessions.issueWebSession(userId, "work-item-import-http")); }

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
                INSERT INTO yumpoo.project (id,company_id,workspace_id,project_code,name,
                    lifecycle,owner_user_id,row_version,created_at,
                    created_by_user_id,updated_at,updated_by_user_id)
                VALUES (:id,:companyId,:workspaceId,'IMPORT_WORK','Import Work',
                    'ACTIVE',:ownerId,0,transaction_timestamp(),:ownerId,
                    transaction_timestamp(),:ownerId)
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
            labels.initialize(COMPANY_ID, PROJECT_ID, clock.instant());
        });
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.activity_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_cell_activity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.attachment_scan_task WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.attachment WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.attachment_quota_usage WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_table_settings WHERE company_id=:id").param("id", COMPANY_ID).update();
        for (String table : java.util.List.of("work_item_time_session", "work_item_timer_state", "work_item_time_revision"))
            jdbc.sql("DELETE FROM yumpoo." + table + " WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_update_mention WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_update WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_relation WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_assignee WHERE company_id=:id").param("id", COMPANY_ID).update();
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
        jdbc.sql("UPDATE yumpoo.app_manager_governance_state SET lifecycle_status='UNINITIALIZED', initialized_at=NULL, "
                + "missing_since=NULL, event_version=0, row_version=0, updated_at=transaction_timestamp() WHERE company_id=:id")
                .param("id", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
