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
class ContentHttpIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    private static final UUID PROJECT_ID = UUID.fromString("29000000-0000-4000-8000-000000000301");
    private static final UUID ARCHIVED_PROJECT_ID = UUID.fromString("29000000-0000-4000-8000-000000000302");
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

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m209-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("m209-owner", "M2-09 Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("m209-member", "M2-09 Member");
            DirectoryMemberProvisioningResult outsiderUser = provisioner.provision("m209-outsider", "M2-09 Outsider");
            DirectoryMemberProvisioningResult managerUser = provisioner.provision("m209-manager", "M2-09 Manager");
            DirectoryMemberProvisioningResult adminUser = provisioner.provision("m209-admin", "M2-09 Company Admin");
            var managerRole = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                    companyQuery.current().companyId(), managerUser.userId(),
                    MaintenanceRoleMode.BOOTSTRAP, "M2-09 HTTP fixture"));
            roleCommands.grant(new PlatformRoleGrantCommand(COMPANY_ID, adminUser.userId(),
                    PlatformRoleCode.COMPANY_ADMIN, adminUser.rowVersion(),
                    new PlatformRoleCommandActor(managerUser.userId(),
                            managerRole.authorizationVersion(), clock.instant()),
                    UUID.randomUUID(), "a".repeat(64), "M2-09 HTTP fixture"));
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            outsider = actor(outsiderUser.userId());
            admin = actor(adminUser.userId());
            createWorkspace();
            createProject(PROJECT_ID, "M2_09_ACTIVE", "ACTIVE", 0, owner.userId(), member.userId());
            createProject(ARCHIVED_PROJECT_ID, "M2_09_ARCHIVED", "ARCHIVED", 1,
                    owner.userId(), member.userId());
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void lifecycleDerivationIdempotencyEtagNoopAndSafeEventsAreClosed() throws Exception {
        assertThat(get("/api/v1/projects/" + PROJECT_ID + "/contents", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/projects/" + PROJECT_ID + "/contents", member).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/projects/" + PROJECT_ID + "/contents", admin).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/projects/" + PROJECT_ID + "/contents", outsider).statusCode()).isEqualTo(404);

        UUID key = UUID.randomUUID();
        String request = createBody("REQ_A", "需求 A", "REQUIREMENTS");
        HttpResponse<String> created = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents",
                owner, request, null, key);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).contains("\"0\"");
        assertThat(created.headers().firstValue("cache-control")).contains("no-store");
        JsonNode createdJson = json.readTree(created.body());
        assertThat(createdJson.path("workItemType").asText()).isEqualTo("REQUIREMENT");
        assertThat(createdJson.path("appliedTemplateKey").asText()).isEqualTo("RND");
        assertThat(createdJson.path("viewConfig").path("table").path("columnOrder").size()).isPositive();
        String location = created.headers().firstValue("location").orElseThrow();

        HttpResponse<String> replay = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents",
                owner, request, null, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents", owner,
                createBody("REQ_B", "需求 B", "REQUIREMENTS"), null, UUID.randomUUID())
                .statusCode()).isEqualTo(201);
        HttpResponse<String> duplicate = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents",
                owner, createBody("REQ_A", "重复代码", "TASKS"), null, UUID.randomUUID());
        assertThat(duplicate.statusCode()).isEqualTo(422);
        assertThat(duplicate.body()).contains("VALIDATION_FAILED", "DUPLICATE", "code");

        String unchanged = updateBody(createdJson.path("name").asText(),
                createdJson.path("description").asText(), "TABLE", createdJson.path("viewConfig"));
        HttpResponse<String> noChange = mutate("PATCH", location, owner, unchanged, "\"0\"", null);
        assertThat(noChange.statusCode()).as(noChange.body()).isEqualTo(200);
        assertThat(noChange.headers().firstValue("etag")).contains("\"0\"");

        HttpResponse<String> updated = mutate("PATCH", location, owner,
                updateBody("需求 A 调整", null, "KANBAN", createdJson.path("viewConfig")), "\"0\"", null);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(updated.headers().firstValue("etag")).contains("\"1\"");
        assertThat(mutate("PATCH", location, owner,
                updateBody("陈旧更新", null, "TABLE", createdJson.path("viewConfig")), "\"0\"", null)
                .statusCode()).isEqualTo(412);

        HttpResponse<String> archived = mutate("POST", location + "/archive", owner, "",
                "\"1\"", UUID.randomUUID());
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        assertThat(mutate("PATCH", location, owner, unchanged, "\"2\"", null).statusCode()).isEqualTo(409);
        HttpResponse<String> restored = mutate("POST", location + "/restore", owner, "",
                "\"2\"", UUID.randomUUID());
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(restored.headers().firstValue("etag")).contains("\"3\"");

        List<String> eventTypes = jdbc.sql("""
                SELECT event_type FROM yumpoo.outbox_event
                 WHERE aggregate_id = :contentId ORDER BY aggregate_version
                """).param("contentId", UUID.fromString(createdJson.path("id").asText()))
                .query(String.class).list();
        assertThat(eventTypes).containsExactly("workitem.content_created", "workitem.content_updated",
                "workitem.content_archived", "workitem.content_restored");
        List<String> payloads = jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE aggregate_id = :contentId ORDER BY aggregate_version
                """).param("contentId", UUID.fromString(createdJson.path("id").asText()))
                .query(String.class).list();
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.has("description")).isFalse();
            assertThat(event.has("viewConfig")).isFalse();
            assertThat(event.has("projectId")).isTrue();
            assertThat(event.has("blueprintCode")).isTrue();
        }
    }

    @Test
    void permissionsArchivedProjectAndConcurrentWritesHaveDeterministicOutcomes() throws Exception {
        String listPath = "/api/v1/projects/" + PROJECT_ID + "/contents";
        assertThat(mutate("POST", listPath, member, createBody("DENIED", "Denied", "TASKS"),
                null, UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(mutate("POST", listPath, admin, createBody("DENIED_ADMIN", "Denied", "TASKS"),
                null, UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(mutate("POST", "/api/v1/projects/" + ARCHIVED_PROJECT_ID + "/contents", owner,
                createBody("ARCHIVED", "Archived", "TASKS"), null, UUID.randomUUID())
                .statusCode()).isEqualTo(409);

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                start.await();
                return mutate("POST", listPath, owner, createBody("RACE", "Race A", "TASKS"),
                        null, UUID.randomUUID());
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                start.await();
                return mutate("POST", listPath, owner, createBody("RACE", "Race B", "TASKS"),
                        null, UUID.randomUUID());
            });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201, 422);
        }

        HttpResponse<String> created = mutate("POST", listPath, owner,
                createBody("PATCH_RACE", "Patch Race", "DEFECTS"), null, UUID.randomUUID());
        String location = created.headers().firstValue("location").orElseThrow();
        JsonNode baseline = json.readTree(created.body());
        CountDownLatch patchStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                patchStart.await();
                return mutate("PATCH", location, owner,
                        updateBody("Patch A", null, "TABLE", baseline.path("viewConfig")), "\"0\"", null);
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                patchStart.await();
                return mutate("PATCH", location, owner,
                        updateBody("Patch B", null, "TABLE", baseline.path("viewConfig")), "\"0\"", null);
            });
            patchStart.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 412);
        }

        JsonNode latest = json.readTree(get(location, owner).body());
        CountDownLatch lockStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> contentWrite = pool.submit(() -> {
                lockStart.await();
                return mutate("PATCH", location, owner,
                        updateBody("Lock Order", null, "TABLE", latest.path("viewConfig")), "\"1\"", null);
            });
            Future<HttpResponse<String>> projectArchive = pool.submit(() -> {
                lockStart.await();
                return mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/archive", owner,
                        "", "\"0\"", UUID.randomUUID());
            });
            lockStart.countDown();
            int writeStatus = contentWrite.get(20, TimeUnit.SECONDS).statusCode();
            int archiveStatus = projectArchive.get(20, TimeUnit.SECONDS).statusCode();
            assertThat(archiveStatus).isEqualTo(200);
            assertThat(writeStatus).isIn(200, 409);
        }
    }

    @Test
    void allFixedTemplatesAndRetiredProjectTemplateRemainAuthoritative() throws Exception {
        String[][] templates = {
                {"29000000-0000-4000-8000-000000000311", "RND", "PRODUCT_DEVELOPMENT", "BACKLOG"},
                {"29000000-0000-4000-8000-000000000312", "PRE_SALES", "PRE_SALES", "TO_ASSESS"},
                {"29000000-0000-4000-8000-000000000313", "IMPLEMENTATION", "IMPLEMENTATION", "PLANNED"},
                {"29000000-0000-4000-8000-000000000314", "HYPERCARE", "HYPERCARE", "OPEN"}
        };
        for (int index = 0; index < templates.length; index++) {
            String[] template = templates[index];
            UUID projectId = UUID.fromString(template[0]);
            createProject(projectId, "M2_09_TEMPLATE_" + index, "ACTIVE", 0,
                    owner.userId(), member.userId(), template[1], 1, template[2]);
            HttpResponse<String> created = mutate("POST", "/api/v1/projects/" + projectId + "/contents",
                    owner, createBody("REQ_" + index, "Template " + index, "REQUIREMENTS"),
                    null, UUID.randomUUID());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            JsonNode result = json.readTree(created.body());
            assertThat(result.path("appliedTemplateKey").asText()).isEqualTo(template[1]);
            assertThat(result.path("workItemType").asText()).isEqualTo("REQUIREMENT");
            assertThat(result.path("viewConfig").path("kanban").path("statusGroups").get(0)
                    .path("statusCodes").get(0).asText()).isEqualTo(template[3]);
        }

        createRetiredTemplate();
        UUID retiredProject = UUID.fromString("29000000-0000-4000-8000-000000000319");
        createProject(retiredProject, "M2_09_RETIRED", "ACTIVE", 0,
                owner.userId(), member.userId(), "RND", 209, "PRODUCT_DEVELOPMENT");
        HttpResponse<String> retiredCreated = mutate("POST",
                "/api/v1/projects/" + retiredProject + "/contents", owner,
                createBody("RETIRED_REQ", "Retired Template", "REQUIREMENTS"), null, UUID.randomUUID());
        assertThat(retiredCreated.statusCode()).as(retiredCreated.body()).isEqualTo(201);
        assertThat(retiredCreated.body()).contains("\"appliedTemplateVersion\":209", "RETIRED_REQ");
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessions.issueWebSession(userId, "m209-http"));
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

    private String createBody(String code, String name, String blueprint) throws Exception {
        var body = json.createObjectNode();
        body.put("code", code); body.put("name", name); body.put("description", "private description");
        body.put("blueprintCode", blueprint);
        return json.writeValueAsString(body);
    }

    private String updateBody(String name, String description, String viewType, JsonNode config) throws Exception {
        var body = json.createObjectNode();
        body.put("name", name);
        if (description == null) body.putNull("description"); else body.put("description", description);
        body.put("defaultViewType", viewType); body.set("viewConfig", config);
        return json.writeValueAsString(body);
    }

    private void createWorkspace() {
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.workspace WHERE id=:id AND code='MAIN'")
                .param("id", WORKSPACE_ID).query(Integer.class).single()).isOne();
    }

    private void createProject(UUID projectId, String code, String lifecycle, long version,
            UUID ownerId, UUID memberId) {
        createProject(projectId, code, lifecycle, version, ownerId, memberId,
                "RND", 1, "PRODUCT_DEVELOPMENT");
    }

    private void createProject(UUID projectId, String code, String lifecycle, long version,
            UUID ownerId, UUID memberId, String templateKey, int templateVersion,
            String projectType) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    project_type, lifecycle, owner_user_id, template_key, template_version,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
                    activated_at, archived_at)
                VALUES (:id, :companyId, :workspaceId, :code, :code, :projectType,
                    :lifecycle, :ownerId, :templateKey, :templateVersion, :version,
                    transaction_timestamp(), :ownerId,
                    transaction_timestamp(), :ownerId, transaction_timestamp(),
                    CASE WHEN :lifecycle = 'ARCHIVED' THEN transaction_timestamp() ELSE NULL END)
                """).param("id", projectId).param("companyId", COMPANY_ID)
                .param("workspaceId", WORKSPACE_ID).param("code", code).param("lifecycle", lifecycle)
                .param("ownerId", ownerId).param("version", version)
                .param("templateKey", templateKey).param("templateVersion", templateVersion)
                .param("projectType", projectType).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id,
                    status, joined_at, joined_by_user_id, row_version)
                VALUES (:ownerMembership, :companyId, :projectId, :ownerId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0),
                    (:memberMembership, :companyId, :projectId, :memberId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0)
                """).param("ownerMembership", UUID.randomUUID()).param("memberMembership", UUID.randomUUID())
                .param("companyId", COMPANY_ID).param("projectId", projectId)
                .param("ownerId", ownerId).param("memberId", memberId).update();
        });
    }

    private void createRetiredTemplate() {
        UUID templateId = UUID.fromString("29000000-0000-4000-8000-000000009209");
        jdbc.sql("""
                INSERT INTO yumpoo.project_template_definition (
                    id, template_key, template_version, version_code, project_type,
                    display_name, lifecycle_status, row_version)
                VALUES (:id, 'RND', 209, 'RND_V209', 'PRODUCT_DEVELOPMENT',
                    'M2-09 retired fixture', 'DRAFT', 0)
                """).param("id", templateId).update();
        jdbc.sql("""
                INSERT INTO yumpoo.project_template_content_blueprint (
                    template_id, content_code, display_name, work_item_type,
                    default_view_type, sort_order)
                VALUES (:id, 'REQUIREMENTS', '需求', 'REQUIREMENT', 'TABLE', 10)
                """).param("id", templateId).update();
        jdbc.sql("""
                INSERT INTO yumpoo.workflow_status_definition (
                    template_id, status_code, display_name, status_category,
                    sort_order, is_initial, is_terminal)
                VALUES (:id, 'BACKLOG', '待规划', 'TODO', 10, true, false),
                       (:id, 'DONE', '已完成', 'DONE', 20, false, true)
                """).param("id", templateId).update();
        jdbc.sql("""
                UPDATE yumpoo.project_template_definition
                   SET lifecycle_status='PUBLISHED', row_version=1,
                       published_at=transaction_timestamp(), published_by_actor_type='SYSTEM',
                       published_by_system_code='M2_09_TEST', updated_at=transaction_timestamp()
                 WHERE id=:id
                """).param("id", templateId).update();
        jdbc.sql("""
                UPDATE yumpoo.project_template_definition
                   SET lifecycle_status='RETIRED', row_version=2,
                       retired_at=transaction_timestamp(), retired_by_user_id=:ownerId,
                       retire_reason='M2-09 retired template compatibility',
                       updated_at=transaction_timestamp()
                 WHERE id=:id
                """).param("id", templateId).param("ownerId", owner.userId()).update();
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id = :companyId")
                    .param("companyId", COMPANY_ID).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id = :companyId")
                    .param("companyId", COMPANY_ID).update();
        });
        jdbc.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("""
                DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN
                    (SELECT id FROM yumpoo.identity_user WHERE company_id = :companyId)
                """).param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("""
                UPDATE yumpoo.app_manager_governance_state
                   SET lifecycle_status='UNINITIALIZED', initialized_at=NULL, missing_since=NULL,
                       event_version=0, row_version=0, updated_at=transaction_timestamp()
                 WHERE company_id=:companyId
                """).param("companyId", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
