package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import com.yumpoo.platform.administration.application.governance.GovernanceIssueQuery;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueQueryUseCase;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueStatus;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueType;
import com.yumpoo.platform.administration.infrastructure.governance.JdbcGovernanceIssueProjection;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusChangeCommand;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusCommandActor;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.AppManagerAvailabilityCoordinator;
import com.yumpoo.platform.identityaccess.application.authorization.GrantPlatformRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.InitialRoleBootstrapCommand;
import com.yumpoo.platform.identityaccess.application.authorization.InitialRoleBootstrapResult;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleAssignmentQueryUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleManagementUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import com.yumpoo.platform.identityaccess.application.authorization.RevokePlatformRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentQuery;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus;
import com.yumpoo.platform.identityaccess.application.authorization.RoleCommandActor;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "yumpoo.outbox.enabled=false"
)
class M109PlatformRoleGovernanceIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID MANAGER_A = UUID.fromString("91000000-0000-4000-8000-000000000109");
    private static final UUID MANAGER_B = UUID.fromString("92000000-0000-4000-8000-000000000109");
    private static final UUID MEMBER = UUID.fromString("93000000-0000-4000-8000-000000000109");

    @Autowired
    private PlatformRoleManagementUseCase managementUseCase;
    @Autowired
    private PlatformRoleMaintenanceUseCase maintenanceUseCase;
    @Autowired
    private PlatformRoleAssignmentQueryUseCase assignmentQuery;
    @Autowired
    private AccountStatusUseCase accountStatusUseCase;
    @Autowired
    private AppManagerAvailabilityCoordinator availabilityCoordinator;
    @Autowired
    private GovernanceIssueQueryUseCase governanceIssueQuery;
    @Autowired
    private JdbcGovernanceIssueProjection governanceIssueProjection;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanUp();
        insertUser(MANAGER_A, "M1-09 Manager A");
        insertUser(MANAGER_B, "M1-09 Manager B");
        insertUser(MEMBER, "M1-09 Member");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void bootstrapIsOneTimeAndBreakGlassOnlyRecoversMissingState() {
        PlatformRoleMutationResult bootstrapped = execute("m109-bootstrap", () ->
                maintenanceUseCase.execute(new MaintenanceRoleCommand(
                        COMPANY_ID, MANAGER_A, MaintenanceRoleMode.BOOTSTRAP, "initial-approval")));

        assertThat(bootstrapped.role()).isEqualTo(ManagedPlatformRole.APP_MANAGER);
        assertThat(state()).isEqualTo("AVAILABLE|1");
        assertThat(systemActor(bootstrapped.assignmentId())).isEqualTo("APP_MANAGER_BOOTSTRAP");
        assertError(() -> maintenanceUseCase.execute(new MaintenanceRoleCommand(
                        COMPANY_ID, MANAGER_B, MaintenanceRoleMode.BOOTSTRAP, "repeat")),
                StandardErrorCode.INVALID_STATE_TRANSITION);
        assertError(() -> maintenanceUseCase.execute(new MaintenanceRoleCommand(
                        COMPANY_ID, MANAGER_B, MaintenanceRoleMode.BREAK_GLASS, "not-missing")),
                StandardErrorCode.INVALID_STATE_TRANSITION);

        markManagerLeftAndReconcile(MANAGER_A);
        assertThat(state()).isEqualTo("MISSING|2");
        PlatformRoleMutationResult recovered = execute("m109-break-glass", () ->
                maintenanceUseCase.execute(new MaintenanceRoleCommand(
                        COMPANY_ID, MANAGER_B, MaintenanceRoleMode.BREAK_GLASS, "incident-42")));

        assertThat(systemActor(recovered.assignmentId())).isEqualTo("APP_MANAGER_BREAK_GLASS");
        assertThat(state()).isEqualTo("AVAILABLE|3");
        assertThat(eventCount("identity.app_manager_missing_detected")).isOne();
        assertThat(eventCount("identity.app_manager_availability_restored")).isOne();
    }

    @Test
    void initialIdentityBootstrapAtomicallyGrantsDistinctPlatformAndCompanyAdministrators() {
        UUID directoryRunId = UUID.randomUUID();
        InitialRoleBootstrapResult result = execute("m115-initial-roles", () ->
                maintenanceUseCase.bootstrapInitialRoles(new InitialRoleBootstrapCommand(
                        COMPANY_ID,
                        MANAGER_A,
                        MANAGER_B,
                        directoryRunId,
                        "approved production initialization"
                )));

        assertThat(activeRoleCount(MANAGER_A)).isOne();
        assertThat(activeRoleCount(MANAGER_B)).isOne();
        assertThat(systemActor(result.appManagerAssignmentId()))
                .isEqualTo("INITIAL_IDENTITY_BOOTSTRAP");
        assertThat(systemActor(result.companyAdminAssignmentId()))
                .isEqualTo("INITIAL_IDENTITY_BOOTSTRAP");
        assertThat(state()).isEqualTo("AVAILABLE|1");
        assertThat(userRowVersion(MANAGER_A)).isEqualTo(1);
        assertThat(userRowVersion(MANAGER_B)).isEqualTo(1);
        assertThat(auditActionCount("APP_MANAGER_BOOTSTRAPPED")).isOne();
        assertThat(auditActionCount("COMPANY_ADMIN_BOOTSTRAPPED")).isOne();
        assertThat(auditActionCount("INITIAL_IDENTITY_BOOTSTRAP_SUCCEEDED")).isOne();
        assertThat(bootstrapAuditSensitiveKeyCount()).isZero();

        assertError(() -> maintenanceUseCase.bootstrapInitialRoles(
                        new InitialRoleBootstrapCommand(
                                COMPANY_ID,
                                MANAGER_A,
                                MANAGER_B,
                                UUID.randomUUID(),
                                "repeat"
                        )),
                StandardErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void initialIdentityBootstrapRollsBackBothRolesWhenAuditFails() {
        jdbcClient.sql("""
                CREATE FUNCTION yumpoo.m110_fail_role_audit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected role audit failure'; END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER m110_fail_role_audit BEFORE INSERT ON yumpoo.security_audit_event
                FOR EACH ROW EXECUTE FUNCTION yumpoo.m110_fail_role_audit()
                """).update();

        assertThatThrownBy(() -> execute("m115-initial-roles-rollback", () ->
                maintenanceUseCase.bootstrapInitialRoles(new InitialRoleBootstrapCommand(
                        COMPANY_ID,
                        MANAGER_A,
                        MANAGER_B,
                        UUID.randomUUID(),
                        "approved production initialization"
                )))).isInstanceOf(RuntimeException.class);

        assertThat(activeRoleCount(MANAGER_A)).isZero();
        assertThat(activeRoleCount(MANAGER_B)).isZero();
        assertThat(userRowVersion(MANAGER_A)).isZero();
        assertThat(userRowVersion(MANAGER_B)).isZero();
        assertThat(state()).isEqualTo("UNINITIALIZED|0");
    }

    @Test
    void appManagerGrantsAndRevokesBothRolesWithReplayAndVersionedSessionInvalidation() {
        PlatformRoleMutationResult manager = bootstrapA();
        UUID companyAdminKey = UUID.randomUUID();
        GrantPlatformRoleCommand companyAdminGrant = grantCommand(
                MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 0, MANAGER_A,
                manager.authorizationVersion(), companyAdminKey, "a".repeat(64));

        var first = execute("m109-company-admin-grant", () -> managementUseCase.grant(companyAdminGrant));
        var replay = execute("m109-company-admin-replay", () -> managementUseCase.grant(companyAdminGrant));
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();

        PlatformRoleMutationResult appManager = grantResult(grantCommand(
                MEMBER, ManagedPlatformRole.APP_MANAGER, 1, MANAGER_A,
                manager.authorizationVersion(), UUID.randomUUID(), "b".repeat(64)));
        assertThat(appManager.authorizationVersion()).isEqualTo(2);
        assertThat(activeRoleCount(MEMBER)).isEqualTo(2);

        PlatformRoleMutationResult revoked = revokeResult(new RevokePlatformRoleCommand(
                COMPANY_ID, appManager.assignmentId(), ManagedPlatformRole.APP_MANAGER, 0,
                recentActor(MANAGER_A, manager.authorizationVersion()),
                UUID.randomUUID(), new RequestHash("c".repeat(64)), "rotation-complete"));
        assertThat(revoked.status()).isEqualTo(RoleAssignmentStatus.REVOKED);
        assertThat(revoked.assignmentRowVersion()).isEqualTo(1);
        assertThat(revoked.authorizationVersion()).isEqualTo(3);

        PlatformRoleMutationResult regrant = grantResult(grantCommand(
                MEMBER, ManagedPlatformRole.APP_MANAGER, 3, MANAGER_A,
                manager.authorizationVersion(), UUID.randomUUID(), "d".repeat(64)));
        assertThat(regrant.assignmentId()).isNotEqualTo(appManager.assignmentId());
        assertThat(historyCount(MEMBER, "APP_MANAGER")).isEqualTo(2);
        assertThat(eventCount("identity.platform_role_granted")).isEqualTo(4);
        assertThat(eventCount("identity.platform_role_revoked")).isOne();
    }

    @Test
    void securityAuditFailureRollsBackRoleUserOutboxAndIdempotencyTogether() {
        PlatformRoleMutationResult manager = bootstrapA();
        UUID idempotencyKey = UUID.randomUUID();
        jdbcClient.sql("""
                CREATE FUNCTION yumpoo.m110_fail_role_audit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected role audit failure'; END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER m110_fail_role_audit BEFORE INSERT ON yumpoo.security_audit_event
                FOR EACH ROW EXECUTE FUNCTION yumpoo.m110_fail_role_audit()
                """).update();

        assertThatThrownBy(() -> execute("m110-role-audit-rollback", () ->
                managementUseCase.grant(grantCommand(
                        MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 0, MANAGER_A,
                        manager.authorizationVersion(), idempotencyKey, "9".repeat(64)))))
                .isInstanceOf(RuntimeException.class);

        assertThat(activeRoleCount(MEMBER)).isZero();
        assertThat(userRowVersion(MEMBER)).isZero();
        assertThat(eventCount("identity.platform_role_granted")).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.idempotency_record
                        WHERE actor_user_id = :actorId AND idempotency_key = :key
                        """)
                .param("actorId", MANAGER_A)
                .param("key", idempotencyKey)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsCompanyAdminMutationCrossCompanyStaleVersionAndExpiredRecentAuthentication() {
        PlatformRoleMutationResult manager = bootstrapA();
        insertDirectRole(MANAGER_B, "COMPANY_ADMIN", "COMPANY");

        assertError(() -> managementUseCase.grant(new GrantPlatformRoleCommand(
                        COMPANY_ID, MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 0,
                        recentActor(MANAGER_B, 0), UUID.randomUUID(),
                        new RequestHash("e".repeat(64)), "company-admin-denied")),
                StandardErrorCode.ACCESS_DENIED);
        assertError(() -> managementUseCase.grant(new GrantPlatformRoleCommand(
                        COMPANY_ID, MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 99,
                        recentActor(MANAGER_A, manager.authorizationVersion()), UUID.randomUUID(),
                        new RequestHash("f".repeat(64)), "stale-target")),
                StandardErrorCode.VERSION_CONFLICT);
        assertError(() -> managementUseCase.grant(new GrantPlatformRoleCommand(
                        COMPANY_ID, MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 0,
                        new RoleCommandActor(MANAGER_A, manager.authorizationVersion(),
                                Instant.now().minusSeconds(901)),
                        UUID.randomUUID(), new RequestHash("0".repeat(64)), "expired-auth")),
                StandardErrorCode.ACCESS_DENIED);
        assertError(() -> managementUseCase.grant(new GrantPlatformRoleCommand(
                        UUID.randomUUID(), MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 0,
                        recentActor(MANAGER_A, manager.authorizationVersion()), UUID.randomUUID(),
                        new RequestHash("1".repeat(64)), "cross-company")),
                StandardErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void concurrentMutationsAndAccountDisableCannotRemoveLastAvailableManager() throws Exception {
        PlatformRoleMutationResult managerA = bootstrapA();
        PlatformRoleMutationResult managerB = grantResult(grantCommand(
                MANAGER_B, ManagedPlatformRole.APP_MANAGER, 0, MANAGER_A,
                managerA.authorizationVersion(), UUID.randomUUID(), "2".repeat(64)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var revokeB = executor.submit(() -> concurrentRevoke(
                    ready, start, managerB.assignmentId(), MANAGER_A,
                    managerA.authorizationVersion(), "3".repeat(64)));
            var revokeA = executor.submit(() -> concurrentRevoke(
                    ready, start, managerA.assignmentId(), MANAGER_B,
                    managerB.authorizationVersion(), "4".repeat(64)));
            ready.await();
            start.countDown();
            revokeA.get();
            revokeB.get();
        }

        assertThat(availableManagerCount()).isOne();
        UUID remaining = activeRoleCount(MANAGER_A) == 1 ? MANAGER_A : MANAGER_B;
        long remainingVersion = userRowVersion(remaining);
        insertDirectRole(MEMBER, "COMPANY_ADMIN", "COMPANY");
        assertError(() -> accountStatusUseCase.change(new AccountStatusChangeCommand(
                        COMPANY_ID, remaining,
                        new AccountStatusCommandActor(MEMBER, 0, Instant.now()),
                        AccountStatus.DISABLED, remainingVersion,
                        UUID.randomUUID(), new RequestHash("5".repeat(64)), "disable-last")),
                StandardErrorCode.INVALID_STATE_TRANSITION);
        assertThat(availableManagerCount()).isOne();
    }

    @Test
    void assignmentAndGovernanceIssueQueriesArePagedReasonFreeAndProjectionIsIdempotent() {
        PlatformRoleMutationResult manager = bootstrapA();
        grantResult(grantCommand(MEMBER, ManagedPlatformRole.COMPANY_ADMIN, 0, MANAGER_A,
                manager.authorizationVersion(), UUID.randomUUID(), "6".repeat(64)));

        var page = assignmentQuery.find(new RoleAssignmentQuery(
                COMPANY_ID, MANAGER_A, MEMBER, ManagedPlatformRole.COMPANY_ADMIN,
                RoleAssignmentStatus.ACTIVE, 0, 20));
        assertThat(page.total()).isOne();
        assertThat(page.items().getFirst().userId()).isEqualTo(MEMBER);

        Instant missingAt = Instant.parse("2026-08-14T08:20:00Z");
        DomainEventEnvelope missing = governanceEvent(
                UUID.randomUUID(), "identity.app_manager_missing_detected", 10, missingAt);
        governanceIssueProjection.consume(missing);
        governanceIssueProjection.consume(missing);
        var open = governanceIssueQuery.find(new GovernanceIssueQuery(
                COMPANY_ID, GovernanceIssueType.APP_MANAGER_MISSING,
                GovernanceIssueStatus.OPEN, 0, 10));
        assertThat(open.total()).isOne();

        governanceIssueProjection.consume(governanceEvent(
                UUID.randomUUID(), "identity.app_manager_availability_restored",
                11, missingAt.plusSeconds(60)));
        var resolved = governanceIssueQuery.find(new GovernanceIssueQuery(
                COMPANY_ID, GovernanceIssueType.APP_MANAGER_MISSING,
                GovernanceIssueStatus.RESOLVED, 0, 10));
        assertThat(resolved.total()).isOne();
        assertThat(resolved.items().getFirst().resolvedEventId()).isNotNull();
    }

    private PlatformRoleMutationResult bootstrapA() {
        return execute("m109-bootstrap-" + UUID.randomUUID(), () ->
                maintenanceUseCase.execute(new MaintenanceRoleCommand(
                        COMPANY_ID, MANAGER_A, MaintenanceRoleMode.BOOTSTRAP, "initial-approval")));
    }

    private GrantPlatformRoleCommand grantCommand(
            UUID target,
            ManagedPlatformRole role,
            long targetVersion,
            UUID actor,
            long actorAuthorizationVersion,
            UUID key,
            String hash
    ) {
        return new GrantPlatformRoleCommand(
                COMPANY_ID, target, role, targetVersion,
                recentActor(actor, actorAuthorizationVersion), key,
                new RequestHash(hash), "approved-change");
    }

    private RoleCommandActor recentActor(UUID actor, long authorizationVersion) {
        return new RoleCommandActor(actor, authorizationVersion, Instant.now().minusSeconds(30));
    }

    private PlatformRoleMutationResult grantResult(GrantPlatformRoleCommand command) {
        var result = execute("m109-grant-" + UUID.randomUUID(), () -> managementUseCase.grant(command));
        try {
            return objectMapper.readValue(result.result().responseJson(), PlatformRoleMutationResult.class);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PlatformRoleMutationResult revokeResult(RevokePlatformRoleCommand command) {
        var result = execute("m109-revoke-" + UUID.randomUUID(), () -> managementUseCase.revoke(command));
        try {
            return objectMapper.readValue(result.result().responseJson(), PlatformRoleMutationResult.class);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Void concurrentRevoke(
            CountDownLatch ready,
            CountDownLatch start,
            UUID assignmentId,
            UUID actorId,
            long actorAuthorizationVersion,
            String hash
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            revokeResult(new RevokePlatformRoleCommand(
                    COMPANY_ID, assignmentId, ManagedPlatformRole.APP_MANAGER, 0,
                    recentActor(actorId, actorAuthorizationVersion), UUID.randomUUID(),
                    new RequestHash(hash), "concurrent-revoke"));
        } catch (ApplicationException exception) {
            assertThat(exception.errorCode()).isIn(
                    StandardErrorCode.ACCESS_DENIED,
                    StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return null;
    }

    private void markManagerLeftAndReconcile(UUID userId) {
        execute("m109-directory-left", () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                var before = availabilityCoordinator.lock(COMPANY_ID);
                jdbcClient.sql("""
                            UPDATE yumpoo.identity_user
                            SET employment_status = 'LEFT', left_at = transaction_timestamp(),
                                left_reason = 'DIRECTORY_SNAPSHOT_MISSING',
                                authorization_version = authorization_version + 1,
                                row_version = row_version + 1,
                                updated_at = transaction_timestamp()
                            WHERE id = :userId
                            """).param("userId", userId).update();
                availabilityCoordinator.reconcile(
                        before, "EMPLOYMENT_LEFT", userId,
                        EventActor.system("WECOM_DIRECTORY_SYNC"));
            });
            return null;
        });
    }

    private DomainEventEnvelope governanceEvent(
            UUID eventId, String eventType, long aggregateVersion, Instant occurredAt
    ) {
        return new DomainEventEnvelope(
                eventId, eventType, 1, occurredAt,
                "AppManagerGovernanceState", COMPANY_ID, aggregateVersion, COMPANY_ID,
                EventActor.system("M1_09_TEST"), "m109-projection", "m109-projection",
                null, objectMapper.createObjectNode());
    }

    private <T> T execute(String requestId, java.util.concurrent.Callable<T> callback) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId))) {
            return callback.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertError(Runnable action, StandardErrorCode expected) {
        assertThatThrownBy(() -> execute("m109-error-" + UUID.randomUUID(), () -> {
            action.run();
            return null;
        })).isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private void insertUser(UUID userId, String name) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, 'ACTIVE', 'ENABLED', :name,
                            transaction_timestamp(), 0, 0,
                            transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("userId", userId)
                .param("companyId", COMPANY_ID)
                .param("name", name)
                .update();
    }

    private void insertDirectRole(UUID userId, String role, String scope) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_system_code, grant_reason,
                            granted_at, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, :role, :scope, :companyId, 'ACTIVE',
                            'SYSTEM', 'M1_09_TEST', 'fixture',
                            transaction_timestamp(), transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("companyId", COMPANY_ID)
                .param("userId", userId)
                .param("role", role)
                .param("scope", scope)
                .update();
    }

    private String state() {
        return jdbcClient.sql("""
                        SELECT lifecycle_status || '|' || event_version
                        FROM yumpoo.app_manager_governance_state WHERE company_id = :companyId
                        """).param("companyId", COMPANY_ID).query(String.class).single();
    }

    private String systemActor(UUID assignmentId) {
        return jdbcClient.sql("""
                        SELECT granted_by_system_code FROM yumpoo.platform_role_assignment
                        WHERE id = :assignmentId
                        """).param("assignmentId", assignmentId).query(String.class).single();
    }

    private int eventCount(String eventType) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type = :eventType")
                .param("eventType", eventType).query(Integer.class).single();
    }

    private int auditActionCount(String action) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.security_audit_event
                        WHERE company_id = :companyId AND action = :action
                        """)
                .param("companyId", COMPANY_ID)
                .param("action", action)
                .query(Integer.class)
                .single();
    }

    private int bootstrapAuditSensitiveKeyCount() {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.security_audit_event event
                        WHERE event.company_id = :companyId
                          AND event.action IN (
                            'APP_MANAGER_BOOTSTRAPPED',
                            'COMPANY_ADMIN_BOOTSTRAPPED',
                            'INITIAL_IDENTITY_BOOTSTRAP_SUCCEEDED'
                          )
                          AND EXISTS (
                            SELECT 1
                            FROM jsonb_object_keys(event.after_summary) AS summary(key)
                            WHERE summary.key IN (
                              'userId', 'externalUserId', 'name', 'authorizationVersion'
                            )
                          )
                        """)
                .param("companyId", COMPANY_ID)
                .query(Integer.class)
                .single();
    }

    private int activeRoleCount(UUID userId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.platform_role_assignment
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """).param("userId", userId).query(Integer.class).single();
    }

    private int historyCount(UUID userId, String role) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.platform_role_assignment
                        WHERE user_id = :userId AND role_code = :role
                        """).param("userId", userId).param("role", role)
                .query(Integer.class).single();
    }

    private int availableManagerCount() {
        return jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.platform_role_assignment assignment
                        JOIN yumpoo.identity_user member ON member.id = assignment.user_id
                        WHERE assignment.company_id = :companyId
                          AND assignment.role_code = 'APP_MANAGER'
                          AND assignment.status = 'ACTIVE'
                          AND member.employment_status = 'ACTIVE'
                          AND member.account_status = 'ENABLED'
                        """).param("companyId", COMPANY_ID).query(Integer.class).single();
    }

    private long userRowVersion(UUID userId) {
        return jdbcClient.sql("SELECT row_version FROM yumpoo.identity_user WHERE id = :userId")
                .param("userId", userId).query(Long.class).single();
    }

    private void cleanUp() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS m110_fail_role_audit ON yumpoo.security_audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS yumpoo.m110_fail_role_audit()").update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.governance_issue WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (:a, :b, :m)")
                .param("a", MANAGER_A).param("b", MANAGER_B).param("m", MEMBER).update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE user_id IN (:a, :b, :m)")
                .param("a", MANAGER_A).param("b", MANAGER_B).param("m", MEMBER).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.platform_role_assignment WHERE user_id IN (:a, :b, :m)")
                .param("a", MANAGER_A).param("b", MANAGER_B).param("m", MEMBER).update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:a, :b, :m)")
                .param("a", MANAGER_A).param("b", MANAGER_B).param("m", MEMBER).update();
        jdbcClient.sql("""
                        UPDATE yumpoo.app_manager_governance_state
                        SET lifecycle_status = 'UNINITIALIZED', initialized_at = NULL,
                            missing_since = NULL, event_version = 0, row_version = 0,
                            updated_at = transaction_timestamp()
                        WHERE company_id = :companyId
                        """).param("companyId", COMPANY_ID).update();
    }
}
