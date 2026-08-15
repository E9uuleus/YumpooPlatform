package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
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
class M113PermissionMatrixIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );

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

    private ActorFixture member;
    private ActorFixture appManager;
    private ActorFixture companyAdmin;
    private ActorFixture dualRole;
    private ActorFixture target;

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m113-matrix-" + UUID.randomUUID())
        )) {
            DirectoryMemberProvisioningResult memberUser = provision("member", "Member");
            DirectoryMemberProvisioningResult managerUser = provision("manager", "App Manager");
            DirectoryMemberProvisioningResult adminUser = provision("admin", "Company Admin");
            DirectoryMemberProvisioningResult dualUser = provision("dual", "Dual Role");
            DirectoryMemberProvisioningResult targetUser = provision("target", "Target");

            PlatformRoleMutationResult managerRole = maintenanceUseCase.execute(
                    new MaintenanceRoleCommand(
                            companyQuery.current().companyId(),
                            managerUser.userId(),
                            MaintenanceRoleMode.BOOTSTRAP,
                            "M1-13 permission matrix"
                    )
            );
            PlatformRoleCommandActor managerActor = new PlatformRoleCommandActor(
                    managerUser.userId(),
                    managerRole.authorizationVersion(),
                    clock.instant()
            );
            PlatformRoleCommandReceipt adminRole = grant(
                    adminUser.userId(),
                    adminUser.rowVersion(),
                    PlatformRoleCode.COMPANY_ADMIN,
                    managerActor,
                    "1"
            );
            PlatformRoleCommandReceipt dualManagerRole = grant(
                    dualUser.userId(),
                    dualUser.rowVersion(),
                    PlatformRoleCode.APP_MANAGER,
                    managerActor,
                    "2"
            );
            PlatformRoleCommandReceipt dualAdminRole = grant(
                    dualUser.userId(),
                    dualManagerRole.mutation().userRowVersion(),
                    PlatformRoleCode.COMPANY_ADMIN,
                    managerActor,
                    "3"
            );

            member = actor(memberUser.userId());
            appManager = actor(managerUser.userId());
            companyAdmin = actor(adminUser.userId());
            dualRole = actor(dualUser.userId());
            target = actor(targetUser.userId());
            assertThat(adminRole.mutation().authorizationVersion()).isOne();
            assertThat(dualAdminRole.mutation().authorizationVersion()).isEqualTo(2);
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void anonymousAndCompanyMemberStayOutsideIdentityAdministration() throws Exception {
        assertThat(get("/api/v1/company", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/company", member).statusCode()).isEqualTo(200);
        assertDenied(get("/api/v1/admin/members", member));
        assertDenied(get("/api/v1/admin/role-assignments", member));
    }

    @Test
    void appManagerCanReadAndGovernRolesButCannotWriteIdentity() throws Exception {
        assertThat(get("/api/v1/admin/members", appManager).statusCode()).isEqualTo(200);
        assertDenied(post(
                "/api/v1/admin/members/" + target.userId() + "/account-disable",
                appManager,
                "{\"reason\":\"M1-13 denied write\"}",
                etag(target.userId())
        ));
        assertDenied(post(
                "/api/v1/admin/directory-sync-runs",
                appManager,
                "",
                null
        ));

        HttpResponse<String> granted = post(
                "/api/v1/admin/company-admin-assignments",
                appManager,
                "{\"userId\":\"" + target.userId()
                        + "\",\"reason\":\"M1-13 role grant\"}",
                etag(target.userId())
        );
        assertThat(granted.statusCode())
                .as("body=%s", granted.body())
                .isEqualTo(201);
    }

    @Test
    void companyAdminCanWriteIdentityButCannotGovernPlatformRoles() throws Exception {
        assertThat(get("/api/v1/admin/members", companyAdmin).statusCode()).isEqualTo(200);
        assertDenied(post(
                "/api/v1/admin/app-manager-assignments",
                companyAdmin,
                "{\"userId\":\"" + member.userId()
                        + "\",\"reason\":\"M1-13 denied role grant\"}",
                etag(member.userId())
        ));

        HttpResponse<String> disabled = post(
                "/api/v1/admin/members/" + target.userId() + "/account-disable",
                companyAdmin,
                "{\"reason\":\"M1-13 account review\"}",
                etag(target.userId())
        );
        assertThat(disabled.statusCode())
                .as("body=%s", disabled.body())
                .isEqualTo(200);
        HttpResponse<String> oldSession = get("/api/v1/company", target);
        assertThat(oldSession.statusCode()).isEqualTo(403);
        assertThat(oldSession.body()).contains("ACCOUNT_DISABLED");
    }

    @Test
    void dualRoleReceivesTheUnionOfBothCapabilities() throws Exception {
        assertThat(get("/api/v1/admin/members", dualRole).statusCode()).isEqualTo(200);
        assertThat(post(
                "/api/v1/admin/company-admin-assignments",
                dualRole,
                "{\"userId\":\"" + member.userId()
                        + "\",\"reason\":\"M1-13 dual role grant\"}",
                etag(member.userId())
        ).statusCode()).isEqualTo(201);
        assertThat(post(
                "/api/v1/admin/members/" + target.userId() + "/account-disable",
                dualRole,
                "{\"reason\":\"M1-13 dual role write\"}",
                etag(target.userId())
        ).statusCode()).isEqualTo(200);
    }

    @Test
    void unavailableUnknownAndLoggedOutSessionsKeepStableSemantics() throws Exception {
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET employment_status = 'LEFT',
                            left_at = transaction_timestamp(),
                            left_reason = 'M1-13 MATRIX',
                            authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE id = :userId
                        """)
                .param("userId", member.userId())
                .update();
        HttpResponse<String> left = get("/api/v1/company", member);
        assertThat(left.statusCode()).isEqualTo(403);
        assertThat(left.body()).contains("ACCOUNT_DISABLED");

        assertThat(get(
                "/api/v1/admin/members/" + UUID.randomUUID(),
                companyAdmin
        ).statusCode()).isEqualTo(404);
        assertThat(getWithCookies(
                "/api/v1/company",
                SessionHttpCookies.SESSION_COOKIE + "=" + "z".repeat(43)
                        + "; " + SessionHttpCookies.CSRF_COOKIE + "=" + "y".repeat(43)
        ).statusCode()).isEqualTo(401);

        HttpResponse<String> logout = post("/api/v1/auth/logout", target, "", null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/company", target).statusCode()).isEqualTo(401);
    }

    private DirectoryMemberProvisioningResult provision(String memberId, String name) {
        return provisioner.provision("m113-" + memberId, "M1-13 " + name);
    }

    private PlatformRoleCommandReceipt grant(
            UUID targetUserId,
            long expectedVersion,
            PlatformRoleCode role,
            PlatformRoleCommandActor actor,
            String hashSeed
    ) {
        return roleCommands.grant(new PlatformRoleGrantCommand(
                COMPANY_ID,
                targetUserId,
                role,
                expectedVersion,
                actor,
                UUID.randomUUID(),
                hashSeed.repeat(64),
                "M1-13 permission matrix"
        ));
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessionService.issueWebSession(userId, "m113-matrix"));
    }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        return getWithCookies(path, actor == null ? null : cookies(actor));
    }

    private HttpResponse<String> getWithCookies(String path, String cookieHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (cookieHeader != null) {
            request.header("Cookie", cookieHeader);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(
            String path,
            ActorFixture actor,
            String json,
            String ifMatch
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Cookie", cookies(actor))
                .header(SessionBoundCsrfTokenRepository.HEADER_NAME,
                        actor.session().csrfCredential().value())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        if (!json.isEmpty()) {
            request.header("Content-Type", "application/json");
        }
        return client.send(
                request.POST(json.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private String etag(UUID userId) throws Exception {
        HttpResponse<String> response = get("/api/v1/admin/members/" + userId, appManager);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("etag").orElseThrow();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String cookies(ActorFixture actor) {
        return SessionHttpCookies.SESSION_COOKIE + "="
                + actor.session().sessionCredential().value()
                + "; " + SessionHttpCookies.CSRF_COOKIE + "="
                + actor.session().csrfCredential().value();
    }

    private static void assertDenied(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .as("body=%s", response.body())
                .isEqualTo(403);
        assertThat(response.body()).contains("ACCESS_DENIED");
    }

    private void cleanUp() {
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
