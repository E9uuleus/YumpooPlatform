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
class ContentHttpIT {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    private static final UUID PROJECT_ID = UUID.fromString("29000000-0000-4000-8000-000000000301");
    private static final String SESSION_COOKIE = "__Host-yumpoo-session";
    private static final String CSRF_COOKIE = "__Host-yumpoo-csrf";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

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
    private ActorFixture outsider;

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("content-category-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("category-owner", "Category Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("category-member", "Category Member");
            DirectoryMemberProvisioningResult outsiderUser = provisioner.provision("category-outsider", "Category Outsider");
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            outsider = actor(outsiderUser.userId());
            createProject(owner.userId(), member.userId());
        }
    }

    @AfterEach
    void tearDown() { cleanUp(); }

    @Test
    void ownerManagesCatalogWithStrongEtagAndOldRoutesAreGone() throws Exception {
        String catalogPath = "/api/v1/projects/" + PROJECT_ID + "/contents";
        assertThat(get(catalogPath, null).statusCode()).isEqualTo(401);
        assertThat(get(catalogPath, outsider).statusCode()).isEqualTo(404);

        HttpResponse<String> created = mutate("POST", catalogPath, owner,
                createBody("调研", "BRIGHT_BLUE"), null, UUID.randomUUID());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode first = json.readTree(created.body());
        assertThat(first.path("code").asText()).startsWith("CAT_");
        assertThat(first.path("name").asText()).isEqualTo("调研");
        assertThat(first.path("active").asBoolean()).isTrue();
        assertThat(first.path("protectedContent").asBoolean()).isFalse();
        assertThat(first.has("viewConfig")).isFalse();

        JsonNode memberCatalog = json.readTree(get(catalogPath, member).body());
        assertThat(memberCatalog.path("canManage").asBoolean()).isFalse();
        assertThat(memberCatalog.path("items").size()).isOne();
        assertThat(mutate("POST", catalogPath, member,
                createBody("禁止", "GRAY"), null, UUID.randomUUID()).statusCode()).isEqualTo(403);

        String etag = json.readTree(get(catalogPath, owner).body()).path("etag").asText();
        String itemPath = catalogPath + "/" + first.path("id").asText();
        assertValidation(mutate("PATCH", itemPath, owner,
                updateBody("调研", "AQUAMARINE", false, 10), etag, null), "LAST_ACTIVE_CONTENT");

        assertThat(mutate("POST", catalogPath, owner,
                createBody("任务", "BRIGHT_GREEN"), null, UUID.randomUUID()).statusCode()).isEqualTo(201);
        etag = json.readTree(get(catalogPath, owner).body()).path("etag").asText();
        HttpResponse<String> updated = mutate("PATCH", itemPath, owner,
                updateBody("用户调研", "AQUAMARINE", false, 20), etag, null);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).path("active").asBoolean()).isFalse();

        assertThat(get("/api/v1/contents/" + first.path("id").asText(), owner).statusCode()).isEqualTo(404);
        assertThat(mutate("POST", itemPath + "/archive", owner, "", "\"0\"",
                UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id "
                        + "AND event_version=2 AND event_type IN ('workitem.content_created','workitem.content_updated')")
                .param("id", UUID.fromString(first.path("id").asText())).query(Long.class).single())
                .isEqualTo(2L);
    }

    @Test
    void unusedCustomCategoryCanBeSoftDeletedButProtectedOrUsedCategoryCannot() throws Exception {
        String catalogPath = "/api/v1/projects/" + PROJECT_ID + "/contents";
        JsonNode deletable = json.readTree(mutate("POST", catalogPath, owner,
                createBody("一次性", "GRAY"), null, UUID.randomUUID()).body());
        mutate("POST", catalogPath, owner, createBody("保留项", "BRIGHT_GREEN"), null, UUID.randomUUID());
        String etag = json.readTree(get(catalogPath, owner).body()).path("etag").asText();
        assertThat(mutate("DELETE", catalogPath + "/" + deletable.path("id").asText(), owner,
                "", etag, null).statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM yumpoo.content WHERE id=:id")
                .param("id", UUID.fromString(deletable.path("id").asText()))
                .query(Boolean.class).single()).isTrue();

        UUID protectedId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.content (id, company_id, project_id, code, name, color_token,
                    sort_order, active, protected_content, ever_used, row_version,
                    created_at, created_by_user_id, updated_at, updated_by_user_id)
                VALUES (:id,:companyId,:projectId,'REQUIREMENTS','需求','BRIGHT_BLUE',30,true,true,
                    false,0,transaction_timestamp(),:actor,transaction_timestamp(),:actor)
                """).param("id", protectedId).param("companyId", COMPANY_ID)
                .param("projectId", PROJECT_ID).param("actor", owner.userId()).update();
        etag = json.readTree(get(catalogPath, owner).body()).path("etag").asText();
        assertValidation(mutate("DELETE", catalogPath + "/" + protectedId, owner, "", etag, null),
                "PROTECTED_CONTENT");

        UUID usedId = UUID.fromString(json.readTree(mutate("POST", catalogPath, owner,
                createBody("已使用", "DARK_RED"), null, UUID.randomUUID()).body()).path("id").asText());
        jdbc.sql("UPDATE yumpoo.content SET ever_used=true WHERE id=:id").param("id", usedId).update();
        etag = json.readTree(get(catalogPath, owner).body()).path("etag").asText();
        assertValidation(mutate("DELETE", catalogPath + "/" + usedId, owner, "", etag, null),
                "CONTENT_IN_USE");
    }

    private void assertValidation(HttpResponse<String> response, String fieldCode) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        JsonNode error = json.readTree(response.body());
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.path("fieldErrors").toString()).contains(fieldCode);
    }

    private ActorFixture actor(UUID userId) { return new ActorFixture(userId, sessions.issueWebSession(userId, "content-category-http")); }

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
    private String createBody(String name, String color) throws Exception {
        return json.writeValueAsString(java.util.Map.of("name", name, "colorToken", color));
    }
    private String updateBody(String name, String color, boolean active, int sortOrder) throws Exception {
        return json.writeValueAsString(java.util.Map.of("name", name, "colorToken", color,
                "active", active, "sortOrder", sortOrder));
    }

    private void createProject(UUID ownerId, UUID memberId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    project_type, lifecycle, owner_user_id, template_key, template_version,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id, activated_at)
                VALUES (:id,:companyId,:workspaceId,'CATEGORY_TEST','Category Test',
                    'PRODUCT_DEVELOPMENT','ACTIVE',:ownerId,'RND',1,0,transaction_timestamp(),
                    :ownerId,transaction_timestamp(),:ownerId,transaction_timestamp())
                """).param("id", PROJECT_ID).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("ownerId", ownerId).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id,
                    status, joined_at, joined_by_user_id, row_version)
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
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content_catalog_version WHERE company_id=:companyId")
                .param("companyId", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
        });
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (SELECT id FROM yumpoo.identity_user WHERE company_id=:companyId)").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:companyId").param("companyId", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
