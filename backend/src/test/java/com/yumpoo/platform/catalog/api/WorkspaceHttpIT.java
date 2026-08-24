package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandPort;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandReceipt;
import com.yumpoo.platform.identityaccess.api.PlatformRoleGrantCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false"
)
class WorkspaceHttpIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String SESSION_COOKIE = "__Host-yumpoo-session";
    private static final String CSRF_COOKIE = "__Host-yumpoo-csrf";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;
    @Autowired
    private IdentityAcceptanceFixtureProvisioner provisioner;
    @Autowired
    private PlatformRoleMaintenanceUseCase maintenanceUseCase;
    @Autowired
    private PlatformRoleCommandPort roleCommands;
    @Autowired
    private CompanyConfigurationQuery companyQuery;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private Clock clock;
    @Autowired
    private ObjectMapper objectMapper;

    private ActorFixture member;
    private ActorFixture admin;

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m202-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult memberUser = provisioner.provision(
                    "m202-member", "M2-02 Member");
            DirectoryMemberProvisioningResult managerUser = provisioner.provision(
                    "m202-manager", "M2-02 App Manager");
            DirectoryMemberProvisioningResult adminUser = provisioner.provision(
                    "m202-admin", "M2-02 Company Admin");

            PlatformRoleMutationResult managerRole = maintenanceUseCase.execute(
                    new MaintenanceRoleCommand(
                            companyQuery.current().companyId(), managerUser.userId(),
                            MaintenanceRoleMode.BOOTSTRAP, "M2-02 HTTP fixture"));
            PlatformRoleCommandActor managerActor = new PlatformRoleCommandActor(
                    managerUser.userId(), managerRole.authorizationVersion(), clock.instant());
            PlatformRoleCommandReceipt adminRole = roleCommands.grant(new PlatformRoleGrantCommand(
                    COMPANY_ID,
                    adminUser.userId(),
                    PlatformRoleCode.COMPANY_ADMIN,
                    adminUser.rowVersion(),
                    managerActor,
                    UUID.randomUUID(),
                    "a".repeat(64),
                    "M2-02 HTTP fixture"
            ));
            assertThat(adminRole.mutation().authorizationVersion()).isOne();
            member = actor(memberUser.userId());
            admin = actor(adminUser.userId());
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void authenticationAndRetiredRoutesProtectMainWorkspace() throws Exception {
        assertThat(get("/api/v1/workspaces", null).statusCode()).isEqualTo(401);
        HttpResponse<String> listed = get("/api/v1/workspaces", member);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("\"code\":\"MAIN\"", "\"status\":\"ACTIVE\"");

        HttpResponse<String> rejectedCreation = mutate(
                "POST", "/api/v1/workspaces", admin,
                "{\"code\":\"SECONDARY\",\"name\":\"其他空间\",\"description\":null,\"sortOrder\":1}",
                null, UUID.randomUUID(), true);
        assertThat(rejectedCreation.statusCode()).isEqualTo(409);
        assertThat(rejectedCreation.body()).contains("INVALID_STATE_TRANSITION");
    }

    @Test
    void administratorPatchesOnlyNameAndDescriptionWithStrongEtag() throws Exception {
        var main = objectMapper.readTree(get("/api/v1/workspaces", member).body()).path("items").get(0);
        String location = "/api/v1/workspaces/" + main.path("id").asText();
        assertThat(get(location, member).statusCode()).isEqualTo(200);
        HttpResponse<String> missingIfMatch = mutate(
                "PATCH", location, admin, updateBody("研发主空间", null),
                null, null, true);
        assertThat(missingIfMatch.statusCode()).isEqualTo(428);

        HttpResponse<String> memberWrite = mutate(
                "PATCH", location, member, updateBody("无权限", null),
                "\"0\"", null, true);
        assertThat(memberWrite.statusCode()).isEqualTo(403);

        HttpResponse<String> updated = mutate(
                "PATCH", location, admin, updateBody("研发主空间", "统一项目归属"),
                "\"0\"", null, true);
        assertThat(updated.statusCode()).as("body=%s", updated.body()).isEqualTo(200);
        assertThat(updated.headers().firstValue("etag")).contains("\"1\"");
        assertThat(updated.body()).contains("\"code\":\"MAIN\"", "\"sortOrder\":0", "\"status\":\"ACTIVE\"");

        HttpResponse<String> stale = mutate(
                "PATCH", location, admin, updateBody("旧版本", null),
                "\"0\"", null, true);
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(stale.body()).contains("VERSION_CONFLICT");

        HttpResponse<String> rejectedArchive = mutate(
                "POST", location + "/archive", admin, "", "\"1\"",
                UUID.randomUUID(), true);
        assertThat(rejectedArchive.statusCode()).isEqualTo(409);
        assertThat(rejectedArchive.body()).contains("INVALID_STATE_TRANSITION");

        HttpResponse<String> rejectedRestore = mutate(
                "POST", location + "/restore", admin, "", "\"1\"",
                UUID.randomUUID(), true);
        assertThat(rejectedRestore.statusCode()).isEqualTo(409);
        assertThat(rejectedRestore.body()).contains("INVALID_STATE_TRANSITION");
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessionService.issueWebSession(userId, "m202-http"));
    }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (actor != null) {
            request.header("Cookie", cookies(actor));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mutate(
            String method,
            String path,
            ActorFixture actor,
            String json,
            String ifMatch,
            UUID idempotencyKey,
            boolean includeCsrf
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Cookie", cookies(actor));
        if (includeCsrf) {
            request.header(CSRF_HEADER,
                    actor.session().csrfCredential().value());
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey.toString());
        }
        if (!json.isEmpty()) {
            request.header("Content-Type", "application/json");
        }
        HttpRequest.BodyPublisher publisher = json.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        request.method(method, publisher);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return URI.create(path);
        }
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String updateBody(String name, String description) {
        String encodedDescription = description == null ? "null" : "\"" + description + "\"";
        return "{\"name\":\"" + name + "\",\"description\":" + encodedDescription + "}";
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "="
                + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "="
                + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbcClient.sql("""
                UPDATE yumpoo.workspace
                   SET name='主工作空间', description=NULL, row_version=0,
                       created_by_user_id=NULL, updated_by_user_id=NULL,
                       updated_at=created_at
                 WHERE company_id=:companyId
                """).param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.idempotency_record
                        WHERE actor_user_id IN (
                            SELECT id FROM yumpoo.identity_user WHERE company_id = :companyId
                        )
                        """).param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.external_identity WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("""
                        UPDATE yumpoo.app_manager_governance_state
                        SET lifecycle_status = 'UNINITIALIZED', initialized_at = NULL,
                            missing_since = NULL, event_version = 0, row_version = 0,
                            updated_at = transaction_timestamp()
                        WHERE company_id = :companyId
                        """).param("companyId", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {
    }
}
