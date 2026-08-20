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
    void authenticationCsrfAndRoleBoundaryProtectWorkspaceMutations() throws Exception {
        assertThat(get("/api/v1/workspaces", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/workspaces", member).statusCode()).isEqualTo(200);

        HttpResponse<String> noCsrf = mutate(
                "POST", "/api/v1/workspaces", admin,
                createBody("CSRF_PROBE", "CSRF Probe"), null, UUID.randomUUID(), false);
        assertThat(noCsrf.statusCode()).isEqualTo(403);

        HttpResponse<String> memberWrite = mutate(
                "POST", "/api/v1/workspaces", member,
                createBody("MEMBER_DENIED", "Member denied"), null, UUID.randomUUID(), true);
        assertThat(memberWrite.statusCode()).isEqualTo(403);
        assertThat(memberWrite.body()).contains("ACCESS_DENIED");
    }

    @Test
    void administratorLifecycleUsesStrongEtagIdempotencyAndHiddenArchivedReads() throws Exception {
        UUID createKey = UUID.randomUUID();
        String body = createBody("DELIVERY", "交付空间");
        HttpResponse<String> created = mutate(
                "POST", "/api/v1/workspaces", admin, body, null, createKey, true);
        assertThat(created.statusCode()).as("body=%s", created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).contains("\"0\"");
        String location = created.headers().firstValue("location").orElseThrow();

        HttpResponse<String> replay = mutate(
                "POST", "/api/v1/workspaces", admin, body, null, createKey, true);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(replay.body())).isEqualTo(objectMapper.readTree(created.body()));
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.outbox_event
                         WHERE event_type = 'catalog.workspace_created'
                        """).query(Integer.class).single()).isOne();

        HttpResponse<String> reused = mutate(
                "POST", "/api/v1/workspaces", admin,
                createBody("DIFFERENT", "不同请求"), null, createKey, true);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("IDEMPOTENCY_KEY_REUSED");

        assertThat(get(location, member).statusCode()).isEqualTo(200);
        HttpResponse<String> missingIfMatch = mutate(
                "PATCH", location, admin, updateBody("交付空间", null, 10),
                null, null, true);
        assertThat(missingIfMatch.statusCode()).isEqualTo(428);

        HttpResponse<String> updated = mutate(
                "PATCH", location, admin, updateBody("交付 Workspace", null, 20),
                "\"0\"", null, true);
        assertThat(updated.statusCode()).as("body=%s", updated.body()).isEqualTo(200);
        assertThat(updated.headers().firstValue("etag")).contains("\"1\"");

        HttpResponse<String> stale = mutate(
                "PATCH", location, admin, updateBody("旧版本", null, 20),
                "\"0\"", null, true);
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(stale.body()).contains("VERSION_CONFLICT");

        HttpResponse<String> archived = mutate(
                "POST", location + "/archive", admin, "", "\"1\"", UUID.randomUUID(), true);
        assertThat(archived.statusCode()).as("body=%s", archived.body()).isEqualTo(200);
        assertThat(archived.headers().firstValue("etag")).contains("\"2\"");
        assertThat(get(location, member).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/workspaces?status=ARCHIVED", member).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/workspaces?status=ARCHIVED", admin).body()).contains("DELIVERY");

        HttpResponse<String> invalidArchive = mutate(
                "POST", location + "/archive", admin, "", "\"2\"", UUID.randomUUID(), true);
        assertThat(invalidArchive.statusCode()).isEqualTo(409);
        assertThat(invalidArchive.body()).contains("INVALID_STATE_TRANSITION");
    }

    @Test
    void duplicateCodeReturnsStableFieldValidation() throws Exception {
        assertThat(mutate(
                "POST", "/api/v1/workspaces", admin,
                createBody("DUPLICATE", "第一个"), null, UUID.randomUUID(), true).statusCode())
                .isEqualTo(201);

        HttpResponse<String> duplicate = mutate(
                "POST", "/api/v1/workspaces", admin,
                createBody("DUPLICATE", "第二个"), null, UUID.randomUUID(), true);
        assertThat(duplicate.statusCode()).isEqualTo(422);
        assertThat(duplicate.body()).contains("VALIDATION_FAILED", "ALREADY_EXISTS", "code");
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

    private static String createBody(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name
                + "\",\"description\":null,\"sortOrder\":10}";
    }

    private static String updateBody(String name, String description, int sortOrder) {
        String encodedDescription = description == null ? "null" : "\"" + description + "\"";
        return "{\"name\":\"" + name + "\",\"description\":" + encodedDescription
                + ",\"sortOrder\":" + sortOrder + "}";
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "="
                + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "="
                + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbcClient.sql("DELETE FROM yumpoo.workspace WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
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
