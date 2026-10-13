package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"yumpoo.outbox.enabled=false","yumpoo.projects.deletion.purge-poll-delay=1h"})
class ProjectStatisticsHttpIT {
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
    @Autowired private PlatformRoleMaintenanceUseCase maintenanceUseCase;
    @Autowired private CompanyConfigurationQuery companyQuery;

    private ActorFixture owner;
    private ActorFixture member;
    private UUID requirementsId;
    private UUID tasksId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("work-item-category-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("dashboard-owner", "Work Category Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("dashboard-member", "Work Category Member");
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            createProject(PROJECT_ID, "CATEGORY_WORK", owner.userId(), member.userId());
            requirementsId = createContent(PROJECT_ID, "需求", "BRIGHT_BLUE");
            tasksId = createContent(PROJECT_ID, "任务", "BRIGHT_GREEN");
        }
    }

    @AfterEach
    void tearDown() { cleanUp(); }

    @Test
    void membersAndNonMemberAdminsReadFixedProjectStatistics() throws Exception {
        ActorFixture admin;
        ActorFixture outsider;
        try (var ignored = RequestCorrelationContext.open(RequestCorrelation.root("project-statistics-" + UUID.randomUUID()))) {
            var adminUser = provisioner.provision("project-statistics-admin", "Statistics Admin");
            maintenanceUseCase.execute(new MaintenanceRoleCommand(companyQuery.current().companyId(), adminUser.userId(),
                    MaintenanceRoleMode.BOOTSTRAP, "PS-1 project statistics fixture"));
            admin = actor(adminUser.userId());
            outsider = actor(provisioner.provision("project-statistics-outsider", "Statistics Outsider").userId());
        }
        var item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "项目统计计时"), null, UUID.randomUUID()));
        var time = json.createObjectNode().put("startedAt", "2026-10-07T04:00:00Z").put("stoppedAt", "2026-10-07T05:00:00Z");
        ok(mutate("POST", "/api/v1/work-items/" + item.path("id").asText() + "/time-sessions", member, time.toString(), null, UUID.randomUUID()));
        var widget = chartWidget();
        var filters = json.createObjectNode().put("includeArchived", false).put("hasTime", false);
        var query = json.createObjectNode().set("filters", filters).set("widgets", json.createArrayNode().add(widget));
        String path = "/api/v1/projects/" + PROJECT_ID + "/statistics";
        for (var reader : List.of(member, admin)) {
            var snapshot = ok(mutate("POST", path + "/query", reader, query.toString(), null, null));
            assertThat(snapshot.path("charts").get(0).path("points").get(0).path("value").asDouble()).isEqualTo(1);
        }
        var items = json.createObjectNode().put("offset", 0).put("limit", 10).set("filters", filters).set("widget", widget);
        assertThat(ok(mutate("POST", path + "/items/query", admin, items.toString(), null, null)).path("totalElements").asLong()).isEqualTo(1);
        var table = json.createObjectNode().put("projectId", PROJECT_ID.toString()).set("filters", filters)
                .set("widget", widget).set("table", json.createObjectNode().put("limit", 25));
        var adminRows = ok(mutate("POST", path + "/table/query", admin, table.toString(), null, null)).path("items");
        assertThat(adminRows.size()).isEqualTo(1);
        assertThat(adminRows.get(0).path("capabilities").path("canEditFields").asBoolean()).isFalse();
        var log = ok(get(path + "/worklog?from=2026-10-05&to=2026-10-11", member));
        assertThat(log.path("entries").get(0).path("durationMs").asLong()).isEqualTo(3600000);
        assertThat(log.path("members").get(0).path("projectMember").asBoolean()).isTrue();
        assertThat(mutate("POST", path + "/query", outsider, query.toString(), null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void archivedProjectStatisticsAreNotFound() throws Exception {
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED',archived_at=transaction_timestamp(),updated_at=transaction_timestamp() WHERE id=:id")
                .param("id", PROJECT_ID).update();
        var query = json.createObjectNode().set("filters", json.createObjectNode().put("includeArchived", false).put("hasTime", false))
                .set("widgets", json.createArrayNode().add(chartWidget()));
        String path = "/api/v1/projects/" + PROJECT_ID + "/statistics";
        assertThat(mutate("POST", path + "/query", owner, query.toString(), null, null).statusCode()).isEqualTo(404);
        assertThat(get(path + "/worklog?from=2026-10-05&to=2026-10-11", owner).statusCode()).isEqualTo(404);
    }

    @Test
    void missingWidgetsReturnRequiredValidation() throws Exception {
        String path = "/api/v1/projects/" + PROJECT_ID + "/statistics/query";
        assertThat(mutate("POST", path, member, "{\"filters\":{}}", null, null).statusCode()).isEqualTo(400);
        var query = json.createObjectNode().set("filters", json.createObjectNode().put("includeArchived", false).put("hasTime", false));
        for (boolean explicitNull : List.of(false, true)) {
            if (explicitNull) query.putNull("widgets");
            var response = mutate("POST", path, member, query.toString(), null, null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            var error = json.readTree(response.body());
            assertThat(error.path("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.path("fieldErrors").get(0).path("field").asText()).isEqualTo("widgets");
            assertThat(error.path("fieldErrors").get(0).path("code").asText()).isEqualTo("REQUIRED");
        }
    }

    @Test
    void tableUsesPathProjectWhenBodyNamesAnotherReadableProject() throws Exception {
        var otherProjectId = UUID.randomUUID();
        createProject(otherProjectId, "OTHER_STATISTICS", owner.userId(), member.userId());
        var otherContentId = createContent(otherProjectId, "另一个项目的任务", "BRIGHT_GREEN");
        var pathItem = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "路径项目工作项"), null, UUID.randomUUID()));
        created(mutate("POST", "/api/v1/projects/" + otherProjectId + "/work-items", member,
                workItemBody(otherContentId, "请求体项目工作项"), null, UUID.randomUUID()));
        var query = json.createObjectNode().put("projectId", otherProjectId.toString()).set("widget", chartWidget())
                .set("table", json.createObjectNode().put("limit", 25));
        var rows = ok(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/statistics/table/query", member,
                query.toString(), null, null)).path("items");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("id").asText()).isEqualTo(pathItem.path("id").asText());
        assertThat(rows.get(0).path("title").asText()).isEqualTo("路径项目工作项");
        assertThat(rows.get(0).path("capabilities").path("canEditFields").asBoolean()).isTrue();
    }

    private tools.jackson.databind.node.ObjectNode chartWidget() throws Exception {
        return (tools.jackson.databind.node.ObjectNode) json.readTree("""
            {"id":"%s","kind":"CHART","title":"图表","metric":"TOTAL","grouping":"STATUS","sort":"DESC","showLegend":true,"showValues":true,
             "wide":{"x":0,"y":0,"w":2,"h":3},"medium":{"x":0,"y":0,"w":2,"h":3},
             "chart":{"type":"NUMBER","dimension":"NONE","series":"NONE","dateInterval":"MONTH","timezone":"Asia/Shanghai",
             "measure":{"metric":"TOTAL","calculation":"SUM"},"stacked":false,"showLegend":true,"showValues":true,"valueFormat":"VALUE",
             "sort":"VALUE_DESC","limit":0,"showEmpty":true,"projectIds":null,"filters":null,"labels":[],"detailColumns":["status"]}}
            """.formatted(UUID.randomUUID()));
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode created(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private UUID createContent(UUID projectId, String name, String color) throws Exception {
        HttpResponse<String> response = mutate("POST", "/api/v1/projects/" + projectId + "/contents", owner,
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

    private void createProject(UUID projectId, String code, UUID ownerId, UUID memberId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id,company_id,workspace_id,project_code,name,
                    lifecycle,owner_user_id,row_version,created_at,
                    created_by_user_id,updated_at,updated_by_user_id)
                VALUES (:id,:companyId,:workspaceId,:code,'Category Work',
                    'ACTIVE',:ownerId,0,transaction_timestamp(),:ownerId,
                    transaction_timestamp(),:ownerId)
                """).param("id", projectId).param("code", code).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("ownerId", ownerId).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id,company_id,project_id,user_id,status,joined_at,joined_by_user_id,row_version)
                VALUES (:ownerMembership,:companyId,:projectId,:ownerId,'ACTIVE',transaction_timestamp(),:ownerId,0),
                       (:memberMembership,:companyId,:projectId,:memberId,'ACTIVE',transaction_timestamp(),:ownerId,0)
                """).param("ownerMembership", UUID.randomUUID()).param("memberMembership", UUID.randomUUID())
                    .param("companyId", COMPANY_ID).param("projectId", projectId)
                    .param("ownerId", ownerId).param("memberId", memberId).update();
            jdbc.sql("INSERT INTO yumpoo.content_catalog_version (project_id,company_id) VALUES (:projectId,:companyId)")
                    .param("projectId", projectId).param("companyId", COMPANY_ID).update();
            labels.initialize(COMPANY_ID, projectId, clock.instant());
        });
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.project_purge_run WHERE company_id=:id").param("id",COMPANY_ID).update();
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
        jdbc.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("UPDATE yumpoo.app_manager_governance_state SET lifecycle_status='UNINITIALIZED', initialized_at=NULL, missing_since=NULL, "
                + "event_version=0, row_version=0, updated_at=transaction_timestamp() WHERE company_id=:id").param("id", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
