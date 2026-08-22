package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandPort;
import com.yumpoo.platform.identityaccess.api.PlatformRoleGrantCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false")
class WorkItemHttpIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("2a000000-0000-4000-8000-000000000201");
    private static final UUID PROJECT_ID = UUID.fromString("2a000000-0000-4000-8000-000000000301");
    private static final String SESSION_COOKIE = "__Host-yumpoo-session";
    private static final String CSRF_COOKIE = "__Host-yumpoo-csrf";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort private int port;
    @Autowired private IdentityAcceptanceFixtureProvisioner provisioner;
    @Autowired private PlatformRoleMaintenanceUseCase maintenanceUseCase;
    @Autowired private PlatformRoleCommandPort roleCommands;
    @Autowired private CompanyConfigurationQuery companyQuery;
    @Autowired private SessionService sessions;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactionManager;

    private ActorFixture owner;
    private ActorFixture member;
    private ActorFixture outsider;
    private ActorFixture admin;
    private UUID contentId;
    private String contentEtag;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m210-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("m210-owner", "M2-10 Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("m210-member", "M2-10 Member");
            DirectoryMemberProvisioningResult outsiderUser = provisioner.provision("m210-outsider", "M2-10 Outsider");
            DirectoryMemberProvisioningResult managerUser = provisioner.provision("m210-manager", "M2-10 Manager");
            DirectoryMemberProvisioningResult adminUser = provisioner.provision("m210-admin", "M2-10 Company Admin");
            var managerRole = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                    companyQuery.current().companyId(), managerUser.userId(),
                    MaintenanceRoleMode.BOOTSTRAP, "M2-10 HTTP fixture"));
            roleCommands.grant(new PlatformRoleGrantCommand(COMPANY_ID, adminUser.userId(),
                    PlatformRoleCode.COMPANY_ADMIN, adminUser.rowVersion(),
                    new PlatformRoleCommandActor(managerUser.userId(),
                            managerRole.authorizationVersion(), clock.instant()),
                    UUID.randomUUID(), "a".repeat(64), "M2-10 HTTP fixture"));
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            outsider = actor(outsiderUser.userId());
            admin = actor(adminUser.userId());
            createWorkspace();
            createProject(owner.userId(), member.userId());
            JsonNode content = createContent("TASKS_MAIN", "任务主表");
            contentId = UUID.fromString(content.path("id").asText());
            contentEtag = "\"" + content.path("rowVersion").asLong() + "\"";
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void creationIsIdempotentNumberedSafeAndQueryableByStatus() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        assertThat(get(collection, null).statusCode()).isEqualTo(401);
        assertThat(get(collection, outsider).statusCode()).isEqualTo(404);
        assertThat(get(collection, admin).statusCode()).isEqualTo(200);

        UUID key = UUID.randomUUID();
        String firstBody = workItemBody("  第一项  ", "MEDIUM", "  纯文本描述  ", "   ");
        HttpResponse<String> created = mutate("POST", collection, member, firstBody, null, key);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).isEmpty();
        assertThat(created.headers().firstValue("location")).isPresent();
        JsonNode first = json.readTree(created.body());
        assertThat(first.path("itemNo").asText()).isEqualTo("M2_10_ACTIVE-1");
        assertThat(first.path("title").asText()).isEqualTo("第一项");
        assertThat(first.path("type").asText()).isEqualTo("TASK");
        assertThat(first.path("statusCode").asText()).isEqualTo("BACKLOG");
        assertThat(first.path("statusCategory").asText()).isEqualTo("TODO");
        assertThat(first.path("description").asText()).isEqualTo("纯文本描述");
        assertThat(first.path("notes").isNull()).isTrue();

        HttpResponse<String> replay = mutate("POST", collection, member, firstBody, null, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(created.body());
        HttpResponse<String> secondCreated = mutate("POST", collection, owner,
                workItemBody("第二项", "HIGH", null, "内部备注"), null, UUID.randomUUID());
        assertThat(secondCreated.statusCode()).as(secondCreated.body()).isEqualTo(201);
        assertThat(json.readTree(secondCreated.body()).path("itemNo").asText())
                .isEqualTo("M2_10_ACTIVE-2");

        JsonNode page = json.readTree(get(collection + "?page=0&size=1", member).body());
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asLong()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("itemNo").asText()).isEqualTo("M2_10_ACTIVE-2");
        JsonNode grouped = json.readTree(get(collection + "?status=BACKLOG&status=DONE", member).body());
        assertThat(grouped.path("totalElements").asLong()).isEqualTo(2);
        assertThat(get(collection + "?status=NOT_A_STATUS", member).statusCode()).isEqualTo(422);
        assertThat(get(created.headers().firstValue("location").orElseThrow(), member).body())
                .contains("纯文本描述", "M2_10_ACTIVE-1");

        assertThat(jdbc.sql("SELECT last_sequence FROM yumpoo.work_item_project_counter WHERE project_id=:id")
                .param("id", PROJECT_ID).query(Long.class).single()).isEqualTo(2L);
        List<String> payloads = jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE event_type='workitem.work_item_created' ORDER BY occurred_at
                """).query(String.class).list();
        assertThat(payloads).hasSize(2);
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.has("description")).isFalse();
            assertThat(event.has("notes")).isFalse();
            assertThat(event.has("workItemId")).isTrue();
            assertThat(event.has("reporterUserId")).isTrue();
        }
    }

    @Test
    void permissionsAndRealOpenItemArchiveBlockersAreEnforced() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        String body = workItemBody("阻塞归档", "URGENT", null, null);
        assertThat(mutate("POST", collection, admin, body, null, UUID.randomUUID()).statusCode())
                .isEqualTo(403);
        assertThat(mutate("POST", collection, outsider, body, null, UUID.randomUUID()).statusCode())
                .isEqualTo(404);
        assertThat(mutate("POST", collection, member, body, null, UUID.randomUUID()).statusCode())
                .isEqualTo(201);

        HttpResponse<String> contentArchive = mutate("POST", "/api/v1/contents/" + contentId + "/archive",
                owner, "", contentEtag, UUID.randomUUID());
        assertThat(contentArchive.statusCode()).as(contentArchive.body()).isEqualTo(409);
        assertThat(contentArchive.body()).contains("CONTENT_ARCHIVE_BLOCKED", "OPEN_WORK_ITEMS", "\"count\":1");

        HttpResponse<String> projectArchive = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/archive",
                owner, "", "\"0\"", UUID.randomUUID());
        assertThat(projectArchive.statusCode()).as(projectArchive.body()).isEqualTo(409);
        assertThat(projectArchive.body()).contains("PROJECT_ARCHIVE_BLOCKED", "OPEN_WORK_ITEMS", "\"count\":1");

        var overrideBody = json.createObjectNode();
        overrideBody.put("action", "PROJECT_ARCHIVE_WITH_OPEN_ITEMS");
        overrideBody.put("targetType", "PROJECT");
        overrideBody.put("targetId", PROJECT_ID.toString());
        overrideBody.put("reason", "M2-10 验证治理覆盖保留真实开放工作项数量");
        HttpResponse<String> override = mutate("POST", "/api/v1/admin/governance-overrides", admin,
                json.writeValueAsString(overrideBody), "\"0\"", UUID.randomUUID());
        assertThat(override.statusCode()).as(override.body()).isEqualTo(200);
        assertThat(jdbc.sql("""
                SELECT blocker_counts::text FROM yumpoo.admin_override
                 WHERE target_id=:projectId AND result='SUCCEEDED'
                """).param("projectId", PROJECT_ID).query(String.class).single())
                .contains("OPEN_WORK_ITEMS", "\"count\": 1");
    }

    @Test
    void concurrentIdempotencyAndContentArchiveRaceHaveOnlyClosedOutcomes() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        UUID sharedKey = UUID.randomUUID();
        String sharedBody = workItemBody("同键并发", "LOW", null, null);
        CountDownLatch sharedStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                sharedStart.await();
                return mutate("POST", collection, member, sharedBody, null, sharedKey);
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                sharedStart.await();
                return mutate("POST", collection, member, sharedBody, null, sharedKey);
            });
            sharedStart.countDown();
            HttpResponse<String> one = first.get(20, TimeUnit.SECONDS);
            HttpResponse<String> two = second.get(20, TimeUnit.SECONDS);
            assertThat(one.statusCode()).isEqualTo(201);
            assertThat(two.statusCode()).isEqualTo(201);
            assertThat(two.body()).isEqualTo(one.body());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE content_id=:id")
                .param("id", contentId).query(Long.class).single()).isEqualTo(1L);

        JsonNode racingContent = createContent("TASKS_RACE", "归档竞争");
        UUID racingContentId = UUID.fromString(racingContent.path("id").asText());
        String racingEtag = "\"" + racingContent.path("rowVersion").asLong() + "\"";
        CountDownLatch raceStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> create = pool.submit(() -> {
                raceStart.await();
                return mutate("POST", "/api/v1/contents/" + racingContentId + "/work-items", member,
                        workItemBody("与归档竞争", "MEDIUM", null, null), null, UUID.randomUUID());
            });
            Future<HttpResponse<String>> archive = pool.submit(() -> {
                raceStart.await();
                return mutate("POST", "/api/v1/contents/" + racingContentId + "/archive", owner,
                        "", racingEtag, UUID.randomUUID());
            });
            raceStart.countDown();
            int createStatus = create.get(20, TimeUnit.SECONDS).statusCode();
            int archiveStatus = archive.get(20, TimeUnit.SECONDS).statusCode();
            assertThat(Set.of(List.of(createStatus, archiveStatus)))
                    .isIn(Set.of(List.of(201, 409)), Set.of(List.of(409, 200)));
        }
        String status = jdbc.sql("SELECT status FROM yumpoo.content WHERE id=:id")
                .param("id", racingContentId).query(String.class).single();
        long count = jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE content_id=:id")
                .param("id", racingContentId).query(Long.class).single();
        assertThat(status.equals("ARCHIVED") ? count == 0 : count == 1).isTrue();
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessions.issueWebSession(userId, "m210-http"));
    }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (actor != null) request.header("Cookie", cookies(actor));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mutate(String method, String path, ActorFixture actor, String body,
            String ifMatch, UUID idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Cookie", cookies(actor))
                .header(CSRF_HEADER, actor.session().csrfCredential().value());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
        if (!body.isEmpty()) request.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private JsonNode createContent(String code, String name) throws Exception {
        var body = json.createObjectNode();
        body.put("code", code); body.put("name", name); body.putNull("description");
        body.put("blueprintCode", "TASKS");
        HttpResponse<String> response = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents",
                owner, json.writeValueAsString(body), null, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private String workItemBody(String title, String priority, String description, String notes) throws Exception {
        var body = json.createObjectNode();
        body.put("title", title); body.put("priority", priority);
        if (description == null) body.putNull("description"); else body.put("description", description);
        if (notes == null) body.putNull("notes"); else body.put("notes", notes);
        return json.writeValueAsString(body);
    }

    private void createWorkspace() {
        jdbc.sql("""
                INSERT INTO yumpoo.workspace (id, company_id, code, name, sort_order, status,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id)
                VALUES (:id, :companyId, 'M2_10', 'M2-10', 100, 'ACTIVE', 0,
                    transaction_timestamp(), :actor, transaction_timestamp(), :actor)
                """).param("id", WORKSPACE_ID).param("companyId", COMPANY_ID)
                .param("actor", owner.userId()).update();
    }

    private void createProject(UUID ownerId, UUID memberId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    project_type, lifecycle, owner_user_id, template_key, template_version,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id, activated_at)
                VALUES (:id, :companyId, :workspaceId, 'M2_10_ACTIVE', 'M2-10 Active',
                    'PRODUCT_DEVELOPMENT', 'ACTIVE', :ownerId, 'RND', 1, 0,
                    transaction_timestamp(), :ownerId, transaction_timestamp(), :ownerId,
                    transaction_timestamp())
                """).param("id", PROJECT_ID).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("ownerId", ownerId).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id,
                    status, joined_at, joined_by_user_id, row_version)
                VALUES (:ownerMembership, :companyId, :projectId, :ownerId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0),
                    (:memberMembership, :companyId, :projectId, :memberId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0)
                """).param("ownerMembership", UUID.randomUUID()).param("memberMembership", UUID.randomUUID())
                    .param("companyId", COMPANY_ID).param("projectId", PROJECT_ID)
                    .param("ownerId", ownerId).param("memberId", memberId).update();
        });
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.work_item WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_project_counter WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id=:id").param("id", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:id").param("id", COMPANY_ID).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:id").param("id", COMPANY_ID).update();
        });
        jdbc.sql("DELETE FROM yumpoo.workspace WHERE id=:id").param("id", WORKSPACE_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("""
                DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN
                    (SELECT id FROM yumpoo.identity_user WHERE company_id=:id)
                """).param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.admin_override WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("""
                UPDATE yumpoo.app_manager_governance_state
                   SET lifecycle_status='UNINITIALIZED', initialized_at=NULL, missing_since=NULL,
                       event_version=0, row_version=0, updated_at=transaction_timestamp()
                 WHERE company_id=:id
                """).param("id", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
