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
class PlatformRoleTierChangeHttpIT {

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
                RequestCorrelation.root("tiers-matrix-" + UUID.randomUUID())
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


            member = actor(memberUser.userId());
            appManager = actor(managerUser.userId());
            companyAdmin = actor(adminUser.userId());
            dualRole = actor(dualUser.userId());
            target = actor(targetUser.userId());
            assertThat(adminRole.mutation().authorizationVersion()).isOne();
            assertThat(dualManagerRole.mutation().authorizationVersion()).isOne();
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void changesTierAtomicallyRevokesSessionsAndReplaysWithoutWriting() throws Exception {
        String before = etag(target.userId());
        UUID key = UUID.randomUUID();
        var promoted = change(target.userId(), appManager, "COMPANY_ADMIN", before, key, true);
        assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
        assertThat(promoted.body()).contains("\"previousRole\":\"COMPANY_MEMBER\"", "\"authorizationVersion\":1");
        assertThat(get("/api/v1/company", target).statusCode()).isEqualTo(401);
        assertThat(change(target.userId(), appManager, "COMPANY_ADMIN", before, key, true).body()).isEqualTo(promoted.body());
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE user_id=:id AND status='ACTIVE'")
                .param("id", target.userId()).query(Integer.class).single()).isOne();
        long auditBefore = auditCount();
        String currentEtag = etag(target.userId());
        var unchanged = change(target.userId(), appManager, "COMPANY_ADMIN", currentEtag, UUID.randomUUID(), true);
        assertThat(unchanged.statusCode()).isEqualTo(200);
        assertThat(unchanged.headers().firstValue("ETag")).contains(currentEtag);
        assertThat(auditCount()).isEqualTo(auditBefore);
        var manager = change(target.userId(), appManager, "APP_MANAGER", currentEtag, UUID.randomUUID(), true);
        assertThat(manager.statusCode()).as(manager.body()).isEqualTo(200);
        assertThat(manager.body()).contains("\"authorizationVersion\":2");
        var demoted = change(target.userId(), appManager, "COMPANY_MEMBER", etag(target.userId()), UUID.randomUUID(), true);
        assertThat(demoted.statusCode()).as(demoted.body()).isEqualTo(200);
        assertThat(demoted.body()).contains("\"authorizationVersion\":3");
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE user_id=:id AND status='ACTIVE'")
                .param("id", target.userId()).query(Integer.class).single()).isZero();
        assertThat(auditCount()).isEqualTo(auditBefore + 2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type='identity.platform_role_revoked' AND payload_json->>'userId'=:id")
                .param("id", target.userId().toString()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void enforcesActorCsrfVersionSelfAndAvailabilityGuards() throws Exception {
        String current = etag(target.userId());
        assertThat(change(target.userId(), companyAdmin, "APP_MANAGER", current, UUID.randomUUID(), true).statusCode()).isEqualTo(403);
        assertThat(change(target.userId(), appManager, "APP_MANAGER", current, UUID.randomUUID(), false).statusCode()).isEqualTo(403);
        assertThat(change(target.userId(), appManager, "APP_MANAGER", null, UUID.randomUUID(), true).statusCode()).isEqualTo(428);
        assertThat(change(target.userId(), appManager, "APP_MANAGER", "\"999\"", UUID.randomUUID(), true).statusCode()).isEqualTo(412);
        assertThat(change(appManager.userId(), appManager, "COMPANY_MEMBER", etag(appManager.userId()), UUID.randomUUID(), true).statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/admin/company-admin-assignments", appManager,
                "{\"userId\":\"" + companyAdmin.userId() + "\",\"reason\":\"duplicate\"}", etag(companyAdmin.userId())).statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/admin/members/" + target.userId() + "/account-disable", appManager,
                "{\"reason\":\"availability test\"}", current).statusCode()).isEqualTo(200);
        assertThat(change(target.userId(), appManager, "APP_MANAGER", etag(target.userId()), UUID.randomUUID(), true).statusCode()).isEqualTo(409);
        jdbcClient.sql("UPDATE yumpoo.login_session SET issued_at = issued_at - interval '16 minutes' WHERE user_id=:id")
                .param("id", appManager.userId()).update();
        assertThat(change(target.userId(), appManager, "APP_MANAGER", current, UUID.randomUUID(), true).statusCode()).isEqualTo(403);
    }

    @Test
    void auditFailureRollsBackTierAssignmentAuthorizationAndSessions() throws Exception {
        jdbcClient.sql("""
                CREATE FUNCTION yumpoo.tier_test_reject_audit() RETURNS trigger AS $$
                BEGIN IF NEW.action = 'PLATFORM_ROLE_TIER_CHANGED' THEN
                    RAISE EXCEPTION 'injected tier audit failure'; END IF; RETURN NEW; END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("CREATE TRIGGER tier_test_reject_audit BEFORE INSERT ON yumpoo.security_audit_event FOR EACH ROW EXECUTE FUNCTION yumpoo.tier_test_reject_audit()")
                .update();
        try {
            String version = etag(target.userId());
            assertThat(change(target.userId(), appManager, "COMPANY_ADMIN", version, UUID.randomUUID(), true).statusCode()).isEqualTo(500);
            assertThat(etag(target.userId())).isEqualTo(version);
            assertThat(get("/api/v1/company", target).statusCode()).isEqualTo(200);
            assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE user_id=:id")
                    .param("id", target.userId()).query(Integer.class).single()).isZero();
        } finally {
            jdbcClient.sql("DROP TRIGGER tier_test_reject_audit ON yumpoo.security_audit_event").update();
            jdbcClient.sql("DROP FUNCTION yumpoo.tier_test_reject_audit()").update();
        }
    }

    @Test
    void memberFiltersRepresentAssignedTiers() throws Exception {
        var members = get("/api/v1/admin/members?platformRole=COMPANY_MEMBER", appManager);
        assertThat(members.statusCode()).isEqualTo(200);
        assertThat(members.body()).contains(target.userId().toString()).doesNotContain(companyAdmin.userId().toString());
        var managers = get("/api/v1/admin/members?platformRole=APP_MANAGER", appManager);
        assertThat(managers.body()).contains(appManager.userId().toString()).doesNotContain(target.userId().toString());
    }

    private long auditCount() {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE action='PLATFORM_ROLE_TIER_CHANGED'")
                .query(Long.class).single();
    }

    private HttpResponse<String> change(UUID userId, ActorFixture actor, String tier,
            String version, UUID key, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/admin/members/" + userId + "/platform-role"))
                .header("Cookie", cookies(actor)).header("Idempotency-Key", key.toString())
                .header("Content-Type", "application/json");
        if (version != null) request.header("If-Match", version);
        if (csrf) request.header(SessionBoundCsrfTokenRepository.HEADER_NAME, actor.session().csrfCredential().value());
        return client.send(request.PUT(HttpRequest.BodyPublishers.ofString(
                "{\"role\":\"" + tier + "\",\"reason\":\"tier change test\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    private DirectoryMemberProvisioningResult provision(String memberId, String name) {
        return provisioner.provision("tiers-" + memberId, "M1-13 " + name);
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
        return new ActorFixture(userId, sessionService.issueWebSession(userId, "tiers-matrix"));
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
