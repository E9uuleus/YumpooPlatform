package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandPort;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false")
class ProductHttpIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
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
    @Autowired private SessionService sessionService;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;

    private ActorFixture owner;
    private ActorFixture replacement;
    private ActorFixture admin;

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m203-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("m203-owner", "M2-03 Owner");
            DirectoryMemberProvisioningResult replacementUser = provisioner.provision("m203-replacement", "M2-03 Replacement");
            DirectoryMemberProvisioningResult managerUser = provisioner.provision("m203-manager", "M2-03 App Manager");
            DirectoryMemberProvisioningResult adminUser = provisioner.provision("m203-admin", "M2-03 Company Admin");

            PlatformRoleMutationResult managerRole = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                    companyQuery.current().companyId(), managerUser.userId(), MaintenanceRoleMode.BOOTSTRAP,
                    "M2-03 HTTP fixture"));
            roleCommands.grant(new PlatformRoleGrantCommand(COMPANY_ID, adminUser.userId(),
                    PlatformRoleCode.COMPANY_ADMIN, adminUser.rowVersion(),
                    new PlatformRoleCommandActor(managerUser.userId(), managerRole.authorizationVersion(), clock.instant()),
                    UUID.randomUUID(), "a".repeat(64), "M2-03 HTTP fixture"));
            owner = actor(ownerUser.userId());
            replacement = actor(replacementUser.userId());
            admin = actor(adminUser.userId());
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void productHttpLifecycleEnforcesVisibilityPreconditionsAndIdempotency() throws Exception {
        assertThat(get("/api/v1/products", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/products", owner).statusCode()).isEqualTo(200);

        String body = createBody("HTTP_PRODUCT", "HTTP Product", owner.userId());
        assertThat(mutate("POST", "/api/v1/products", admin, body, null,
                UUID.randomUUID(), false).statusCode()).isEqualTo(403);
        assertThat(mutate("POST", "/api/v1/products", owner, body, null,
                UUID.randomUUID(), true).statusCode()).isEqualTo(403);

        UUID createKey = UUID.randomUUID();
        HttpResponse<String> created = mutate("POST", "/api/v1/products", admin,
                body, null, createKey, true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).contains("\"0\"");
        String location = created.headers().firstValue("location").orElseThrow();

        HttpResponse<String> replay = mutate("POST", "/api/v1/products", admin,
                body, null, createKey, true);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(replay.body())).isEqualTo(objectMapper.readTree(created.body()));
        assertThat(mutate("POST", "/api/v1/products", admin,
                createBody("DIFFERENT", "Different", owner.userId()), null, createKey, true).statusCode())
                .isEqualTo(409);

        assertThat(get(location, owner).statusCode()).isEqualTo(200);
        assertThat(get(location, replacement).statusCode()).isEqualTo(404);
        assertThat(mutate("PATCH", location, owner, updateBody("Changed", null),
                null, null, true).statusCode()).isEqualTo(428);

        HttpResponse<String> updated = mutate("PATCH", location, owner,
                updateBody("Changed", "private"), "\"0\"", null, true);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(updated.headers().firstValue("etag")).contains("\"1\"");
        assertThat(mutate("PATCH", location, owner, updateBody("Stale", null),
                "\"0\"", null, true).statusCode()).isEqualTo(412);

        HttpResponse<String> archived = mutate("POST", location + "/archive", owner,
                "", "\"1\"", UUID.randomUUID(), true);
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        assertThat(get(location, owner).statusCode()).isEqualTo(200);
        assertThat(mutate("PATCH", location, owner, updateBody("Archived write", null),
                "\"2\"", null, true).statusCode()).isEqualTo(409);
        assertThat(mutate("POST", location + "/restore", owner, "", "\"2\"",
                UUID.randomUUID(), true).statusCode()).isEqualTo(403);

        HttpResponse<String> reassigned = mutate("POST", location + "/owner-reassignments", admin,
                reassignmentBody(replacement.userId()), "\"2\"", UUID.randomUUID(), true);
        assertThat(reassigned.statusCode()).as(reassigned.body()).isEqualTo(200);
        assertThat(reassigned.headers().firstValue("etag")).contains("\"3\"");
        assertThat(get(location, owner).statusCode()).isEqualTo(404);
        assertThat(get(location, replacement).statusCode()).isEqualTo(200);

        HttpResponse<String> restored = mutate("POST", location + "/restore", admin,
                "", "\"3\"", UUID.randomUUID(), true);
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(restored.headers().firstValue("etag")).contains("\"4\"");
    }

    @Test
    void invalidOwnerReturnsStableFieldViolation() throws Exception {
        HttpResponse<String> invalid = mutate("POST", "/api/v1/products", admin,
                createBody("INVALID_OWNER", "Invalid owner", UUID.randomUUID()),
                null, UUID.randomUUID(), true);
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("VALIDATION_FAILED", "INVALID_OWNER", "ownerUserId");
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessionService.issueWebSession(userId, "m203-http"));
    }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (actor != null) {
            request.header("Cookie", cookies(actor));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mutate(String method, String path, ActorFixture actor, String json,
                                        String ifMatch, UUID idempotencyKey, boolean includeCsrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Cookie", cookies(actor));
        if (includeCsrf) request.header(CSRF_HEADER, actor.session().csrfCredential().value());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
        if (!json.isEmpty()) request.header("Content-Type", "application/json");
        request.method(method, json.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return URI.create(path);
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String createBody(String code, String name, UUID ownerUserId) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name
                + "\",\"description\":null,\"ownerUserId\":\"" + ownerUserId + "\"}";
    }

    private static String updateBody(String name, String description) {
        String encoded = description == null ? "null" : "\"" + description + "\"";
        return "{\"name\":\"" + name + "\",\"description\":" + encoded + "}";
    }

    private static String reassignmentBody(UUID newOwnerUserId) {
        return "{\"newOwnerUserId\":\"" + newOwnerUserId
                + "\",\"reason\":\"负责人岗位调整并完成交接\"}";
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbcClient.sql("DELETE FROM yumpoo.governance_issue WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.product WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (SELECT id FROM yumpoo.identity_user WHERE company_id = :companyId)")
                .param("companyId", COMPANY_ID).update();
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

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
