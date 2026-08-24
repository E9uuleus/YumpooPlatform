package com.yumpoo.platform.administration.api;

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
class ProjectCreationHttpIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String SESSION_COOKIE = "__Host-yumpoo-session";
    private static final String CSRF_COOKIE = "__Host-yumpoo-csrf";

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
    @Autowired private PlatformTransactionManager transactionManager;

    private ActorFixture member;
    private ActorFixture admin;

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m204-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult memberUser = provisioner.provision(
                    "m204-member", "M2-04 Member");
            DirectoryMemberProvisioningResult managerUser = provisioner.provision(
                    "m204-manager", "M2-04 App Manager");
            DirectoryMemberProvisioningResult adminUser = provisioner.provision(
                    "m204-admin", "M2-04 Company Admin");

            var managerRole = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                    companyQuery.current().companyId(), managerUser.userId(),
                    MaintenanceRoleMode.BOOTSTRAP, "M2-04 HTTP fixture"));
            roleCommands.grant(new PlatformRoleGrantCommand(
                    COMPANY_ID, adminUser.userId(), PlatformRoleCode.COMPANY_ADMIN,
                    adminUser.rowVersion(), new PlatformRoleCommandActor(
                    managerUser.userId(), managerRole.authorizationVersion(), clock.instant()),
                    UUID.randomUUID(), "a".repeat(64), "M2-04 HTTP fixture"));
            member = actor(memberUser.userId());
            admin = actor(adminUser.userId());

        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void authenticationCsrfAndCompanyAdminBoundaryProtectCreation() throws Exception {
        String body = body("DENIED", "PRE_SALES", "PRE_SALES", member.userId());
        assertThat(post(body, null, UUID.randomUUID(), true).statusCode()).isEqualTo(401);
        assertThat(post(body, admin, UUID.randomUUID(), false).statusCode()).isEqualTo(403);

        HttpResponse<String> denied = post(body, member, UUID.randomUUID(), true);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("ACCESS_DENIED");
        assertThat(projectCount()).isZero();
    }

    @Test
    void fourTypesCreateDraftAndReplayExactlyTheSameResponse() throws Exception {
        String[][] mappings = {
                {"HTTP_RND", "PRODUCT_DEVELOPMENT", "RND"},
                {"HTTP_PRE", "PRE_SALES", "PRE_SALES"},
                {"HTTP_IMPL", "IMPLEMENTATION", "IMPLEMENTATION"},
                {"HTTP_HYPER", "HYPERCARE", "HYPERCARE"}
        };
        for (String[] mapping : mappings) {
            UUID key = UUID.randomUUID();
            String body = body(mapping[0], mapping[1], mapping[2], member.userId());
            HttpResponse<String> created = post(body, admin, key, true);
            assertThat(created.statusCode()).as("body=%s", created.body()).isEqualTo(201);
            assertThat(created.headers().firstValue("etag")).contains("\"0\"");
            assertThat(created.headers().firstValue("location").orElseThrow())
                    .startsWith("/api/v1/projects/");
            assertThat(created.body()).contains("\"lifecycle\":\"DRAFT\"", "\"rowVersion\":0")
                    .doesNotContain("contents", "contentCount");

            HttpResponse<String> replay = post(body, admin, key, true);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.headers().firstValue("etag"))
                    .isEqualTo(created.headers().firstValue("etag"));
            assertThat(replay.body()).isEqualTo(created.body());
        }
        assertThat(projectCount()).isEqualTo(4);
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.content")
                .query(Integer.class).single()).isEqualTo(12);
    }

    @Test
    void invalidOwnerWorkspaceTemplateMismatchDuplicateAndKeyReuseAreStable() throws Exception {
        assertValidation(body("BAD_OWNER", "PRE_SALES", "PRE_SALES", UUID.randomUUID()),
                UUID.randomUUID(), "ownerUserId", "INVALID_OWNER");

        assertValidation(body("BAD_TEMPLATE", "PRODUCT_DEVELOPMENT", "PRE_SALES", member.userId()),
                UUID.randomUUID(), "templateKey", "TEMPLATE_TYPE_MISMATCH");

        String duplicate = body("HTTP_DUP", "HYPERCARE", "HYPERCARE", member.userId());
        assertThat(post(duplicate, admin, UUID.randomUUID(), true).statusCode()).isEqualTo(201);
        assertValidation(duplicate, UUID.randomUUID(), "code", "ALREADY_EXISTS");

        UUID reusedKey = UUID.randomUUID();
        assertThat(post(body("KEY_ONE", "PRE_SALES", "PRE_SALES", member.userId()),
                admin, reusedKey, true).statusCode()).isEqualTo(201);
        HttpResponse<String> reused = post(
                body("KEY_TWO", "PRE_SALES", "PRE_SALES", member.userId()),
                admin, reusedKey, true);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    private void assertValidation(String body, UUID key, String field, String reason) throws Exception {
        HttpResponse<String> response = post(body, admin, key, true);
        assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(422);
        assertThat(response.body()).contains("VALIDATION_FAILED", field, reason);
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessionService.issueWebSession(userId, "m204-http"));
    }

    private HttpResponse<String> post(
            String json, ActorFixture actor, UUID idempotencyKey, boolean includeCsrf
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/projects"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey.toString());
        if (actor != null) {
            request.header("Cookie", cookies(actor));
            if (includeCsrf) {
                request.header("X-XSRF-TOKEN", actor.session().csrfCredential().value());
            }
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String body(String code, String type, String template, UUID ownerId) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("code", code);
        body.put("name", "  " + code + "  ");
        body.put("description", "  private description  ");
        body.put("projectType", type);
        body.put("ownerUserId", ownerId.toString());
        body.put("templateKey", template);
        body.put("templateVersion", 1);
        body.putNull("customerName");
        body.put("customerReference", " ");
        body.put("deliverySite", " ");
        body.put("contactNote", " ");
        return objectMapper.writeValueAsString(body);
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private int projectCount() {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.project")
                .query(Integer.class).single();
    }

    private void cleanUp() {
        jdbcClient.sql("DELETE FROM yumpoo.content WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcClient.sql("DELETE FROM yumpoo.project_membership WHERE company_id = :companyId")
                    .param("companyId", COMPANY_ID).update();
            jdbcClient.sql("DELETE FROM yumpoo.project WHERE company_id = :companyId")
                    .param("companyId", COMPANY_ID).update();
        });
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.idempotency_record
                        WHERE actor_user_id IN (
                            SELECT id FROM yumpoo.identity_user WHERE company_id = :companyId)
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
