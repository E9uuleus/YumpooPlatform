package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusChangeCommand;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusCommandActor;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusUseCase;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class M107AccountStatusIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID ACTOR_ID = UUID.fromString(
            "70000000-0000-4000-8000-000000000107"
    );
    private static final UUID TARGET_ID = UUID.fromString(
            "71000000-0000-4000-8000-000000000107"
    );

    @Autowired
    private AccountStatusUseCase useCase;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanUp();
        insertUser(ACTOR_ID, "Account Governance Actor");
        insertUser(TARGET_ID, "Account Governance Target");
        insertCompanyAdmin(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void disableRevokesWebAndElectronSessionsAndReplaysWithoutDuplicateEffects() throws Exception {
        var web = sessionService.issueWebSession(TARGET_ID, "m107-web");
        UUID electronId = insertElectronSession(false);
        UUID expiredId = insertElectronSession(true);
        AccountStatusChangeCommand command = command(
                AccountStatus.DISABLED,
                0,
                UUID.fromString("72000000-0000-4000-8000-000000000107"),
                "a".repeat(64),
                "  security-review-2026-08  "
        );

        var first = execute("m107-disable", command);
        var replay = execute("m107-disable-replay", command);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result().httpStatus()).isEqualTo(first.result().httpStatus());
        assertThat(replay.result().resourceId()).isEqualTo(first.result().resourceId());
        assertThat(replay.result().etag()).isEqualTo(first.result().etag());
        assertThat(objectMapper.readTree(replay.result().responseJson()))
                .isEqualTo(objectMapper.readTree(first.result().responseJson()));
        assertThat(first.result().etag()).isEqualTo("\"1\"");
        assertThat(userState()).isEqualTo(
                "ACTIVE|DISABLED|1|1|security-review-2026-08"
        );
        assertThat(sessionState(web.session().id())).isEqualTo("REVOKED|ACCOUNT_DISABLED");
        assertThat(sessionState(electronId)).isEqualTo("REVOKED|ACCOUNT_DISABLED");
        assertThat(sessionState(expiredId)).isEqualTo("ACTIVE|");
        assertAuthenticationError(web.sessionCredential(), StandardErrorCode.ACCOUNT_DISABLED);
        assertThat(eventCount("identity.user_account_disabled", 1)).isOne();
        assertThat(eventCount("identity.user_sessions_revoked", 2)).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT payload_json ->> 'revokedCount'
                        FROM yumpoo.outbox_event
                        WHERE aggregate_id = :targetId
                          AND event_type = 'identity.user_sessions_revoked'
                          AND event_version = 2
                        """)
                .param("targetId", TARGET_ID)
                .query(String.class)
                .single()).isEqualTo("2");
        assertThat(jdbcClient.sql("""
                        SELECT actor_reason_reference
                        FROM yumpoo.outbox_event
                        WHERE aggregate_id = :targetId
                          AND event_type = 'identity.user_account_disabled'
                        """)
                .param("targetId", TARGET_ID)
                .query(String.class)
                .single()).isEqualTo("security-review-2026-08");
        assertThat(jdbcClient.sql("""
                        SELECT jsonb_exists(payload_json, 'reason')
                        FROM yumpoo.outbox_event
                        WHERE aggregate_id = :targetId
                          AND event_type = 'identity.user_account_disabled'
                        """)
                .param("targetId", TARGET_ID)
                .query(Boolean.class)
                .single()).isFalse();
    }

    @Test
    void enablePreservesDisableFactsAndNeverRestoresOldSessions() {
        var oldSession = sessionService.issueWebSession(TARGET_ID, "m107-old");
        execute("m107-disable-before-enable", command(
                AccountStatus.DISABLED,
                0,
                UUID.randomUUID(),
                "b".repeat(64),
                "security-review"
        ));

        var enabled = execute("m107-enable", command(
                AccountStatus.ENABLED,
                1,
                UUID.randomUUID(),
                "c".repeat(64),
                "review-completed"
        ));

        assertThat(enabled.result().etag()).isEqualTo("\"2\"");
        assertThat(userState()).isEqualTo("ACTIVE|ENABLED|2|2|security-review");
        assertAuthenticationError(oldSession.sessionCredential(), StandardErrorCode.ACCOUNT_DISABLED);
        assertThat(sessionService.issueWebSession(TARGET_ID, "m107-new").session().userId())
                .isEqualTo(TARGET_ID);
        assertThat(eventCount("identity.user_account_enabled", 1)).isOne();
    }

    @Test
    void leftUserCanBeEnabledButStillCannotLogIn() {
        execute("m107-left-disable", command(
                AccountStatus.DISABLED,
                0,
                UUID.randomUUID(),
                "d".repeat(64),
                "manual-disable"
        ));
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET employment_status = 'LEFT',
                            left_at = transaction_timestamp(),
                            left_reason = 'DIRECTORY_SNAPSHOT_MISSING',
                            authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE id = :targetId
                        """)
                .param("targetId", TARGET_ID)
                .update();

        execute("m107-left-enable", command(
                AccountStatus.ENABLED,
                2,
                UUID.randomUUID(),
                "e".repeat(64),
                "employment-reviewed"
        ));

        assertThat(userState()).isEqualTo("LEFT|ENABLED|3|3|manual-disable");
        assertThatThrownBy(() -> sessionService.issueWebSession(TARGET_ID, "m107-left"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    void rejectsHashReuseStaleVersionsRepeatedStateAndHiddenResources() {
        UUID key = UUID.randomUUID();
        execute("m107-error-baseline", command(
                AccountStatus.DISABLED,
                0,
                key,
                "f".repeat(64),
                "baseline"
        ));

        assertError(command(
                AccountStatus.DISABLED,
                1,
                key,
                "0".repeat(64),
                "different"
        ), StandardErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertError(command(
                AccountStatus.ENABLED,
                0,
                UUID.randomUUID(),
                "1".repeat(64),
                "stale-version"
        ), StandardErrorCode.VERSION_CONFLICT);
        assertError(command(
                AccountStatus.DISABLED,
                1,
                UUID.randomUUID(),
                "2".repeat(64),
                "repeated-state"
        ), StandardErrorCode.INVALID_STATE_TRANSITION);
        assertError(new AccountStatusChangeCommand(
                UUID.randomUUID(),
                TARGET_ID,
                new AccountStatusCommandActor(ACTOR_ID, 0, Instant.now()),
                AccountStatus.ENABLED,
                1,
                UUID.randomUUID(),
                new RequestHash("3".repeat(64)),
                "hidden"
        ), StandardErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> command(
                AccountStatus.ENABLED,
                1,
                UUID.randomUUID(),
                "4".repeat(64),
                "x".repeat(161)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED));
    }

    @Test
    void eventAppendFailureRollsBackUserSessionAndIdempotencyTogether() {
        var session = sessionService.issueWebSession(TARGET_ID, "m107-rollback");
        insertConflictingAccountEvent();
        AccountStatusChangeCommand command = command(
                AccountStatus.DISABLED,
                0,
                UUID.randomUUID(),
                "5".repeat(64),
                "rollback-check"
        );

        assertThatThrownBy(() -> execute("m107-event-rollback", command))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(userState()).isEqualTo("ACTIVE|ENABLED|0|0|");
        assertThat(sessionState(session.session().id())).isEqualTo("ACTIVE|");
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.idempotency_record
                        WHERE actor_user_id = :actorId
                          AND idempotency_key = :idempotencyKey
                        """)
                .param("actorId", ACTOR_ID)
                .param("idempotencyKey", command.idempotencyKey())
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void concurrentSessionIssueAndDisableCannotLeaveAnActiveSession() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AccountStatusChangeCommand command = command(
                AccountStatus.DISABLED,
                0,
                UUID.randomUUID(),
                "6".repeat(64),
                "issue-disable-race"
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            var issue = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    sessionService.issueWebSession(TARGET_ID, "m107-race");
                } catch (ApplicationException exception) {
                    assertThat(exception.errorCode())
                            .isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED);
                }
                return null;
            });
            var disable = executor.submit(() -> {
                ready.countDown();
                start.await();
                execute("m107-issue-disable-race", command);
                return null;
            });
            ready.await();
            start.countDown();
            issue.get();
            disable.get();
        }

        assertThat(userState()).startsWith("ACTIVE|DISABLED|1|1|");
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.login_session
                        WHERE user_id = :targetId
                          AND status = 'ACTIVE'
                          AND idle_expires_at > transaction_timestamp()
                          AND absolute_expires_at > transaction_timestamp()
                        """)
                .param("targetId", TARGET_ID)
                .query(Integer.class)
                .single()).isZero();
    }

    private com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult execute(
            String requestId,
            AccountStatusChangeCommand command
    ) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId)
        )) {
            return useCase.change(command);
        }
    }

    private void assertError(AccountStatusChangeCommand command, StandardErrorCode errorCode) {
        assertThatThrownBy(() -> execute("m107-error-" + UUID.randomUUID(), command))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(errorCode));
    }

    private AccountStatusChangeCommand command(
            AccountStatus status,
            long expectedVersion,
            UUID idempotencyKey,
            String hash,
            String reason
    ) {
        return new AccountStatusChangeCommand(
                COMPANY_ID,
                TARGET_ID,
                new AccountStatusCommandActor(ACTOR_ID, 0, Instant.now()),
                status,
                expectedVersion,
                idempotencyKey,
                new RequestHash(hash),
                reason
        );
    }

    private void assertAuthenticationError(
            com.yumpoo.platform.identityaccess.application.session.SessionCredential credential,
            StandardErrorCode expected
    ) {
        assertThatThrownBy(() -> sessionService.authenticate(credential))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private UUID insertElectronSession(boolean expired) {
        UUID sessionId = UUID.randomUUID();
        String fingerprint = sessionId.toString().replace("-", "")
                + sessionId.toString().replace("-", "");
        jdbcClient.sql("""
                        INSERT INTO yumpoo.login_session (
                            id, company_id, user_id, status,
                            session_token_fingerprint, session_key_version,
                            csrf_token_fingerprint, csrf_key_version,
                            issued_authorization_version, client_type, client_version,
                            issued_at, last_seen_at, idle_expires_at, absolute_expires_at,
                            purge_after
                        ) VALUES (
                            :sessionId, :companyId, :targetId, 'ACTIVE',
                            :fingerprint, 'm107', NULL, NULL,
                            0, 'ELECTRON', 'm107-electron',
                            transaction_timestamp() - interval '2 days',
                            transaction_timestamp() - interval '2 days',
                            CASE WHEN :expired THEN transaction_timestamp() - interval '1 day'
                                 ELSE transaction_timestamp() + interval '1 hour' END,
                            transaction_timestamp() + interval '2 hours',
                            transaction_timestamp() + interval '26 hours'
                        )
                        """)
                .param("sessionId", sessionId)
                .param("companyId", COMPANY_ID)
                .param("targetId", TARGET_ID)
                .param("fingerprint", fingerprint)
                .param("expired", expired)
                .update();
        return sessionId;
    }

    private void insertConflictingAccountEvent() {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.outbox_event (
                            event_id, event_type, event_version,
                            aggregate_type, aggregate_id, aggregate_version,
                            company_id, actor_type, actor_user_id, actor_reason_reference,
                            occurred_at, request_id, correlation_id, causation_id,
                            payload_json, status, attempt_count, next_attempt_at, created_at
                        ) VALUES (
                            :eventId, 'identity.user_account_disabled', 1,
                            'User', :targetId, 1,
                            :companyId, 'ADMIN_OVERRIDE', :actorId, 'conflict-fixture',
                            transaction_timestamp(), 'm107-conflict', 'm107-conflict', NULL,
                            '{}'::jsonb, 'PENDING', 0,
                            transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("eventId", UUID.randomUUID())
                .param("targetId", TARGET_ID)
                .param("companyId", COMPANY_ID)
                .param("actorId", ACTOR_ID)
                .update();
    }

    private String userState() {
        return jdbcClient.sql("""
                        SELECT employment_status || '|' || account_status || '|'
                            || authorization_version || '|' || row_version || '|'
                            || COALESCE(account_disabled_reason, '')
                        FROM yumpoo.identity_user
                        WHERE id = :targetId
                        """)
                .param("targetId", TARGET_ID)
                .query(String.class)
                .single();
    }

    private String sessionState(UUID sessionId) {
        return jdbcClient.sql("""
                        SELECT status || '|' || COALESCE(revoke_reason, '')
                        FROM yumpoo.login_session
                        WHERE id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query(String.class)
                .single();
    }

    private int eventCount(String eventType, int eventVersion) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.outbox_event
                        WHERE aggregate_id = :targetId
                          AND event_type = :eventType
                          AND event_version = :eventVersion
                        """)
                .param("targetId", TARGET_ID)
                .param("eventType", eventType)
                .param("eventVersion", eventVersion)
                .query(Integer.class)
                .single();
    }

    private void insertUser(UUID userId, String displayName) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, 'ACTIVE', 'ENABLED',
                            :displayName, transaction_timestamp(),
                            0, 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("userId", userId)
                .param("companyId", COMPANY_ID)
                .param("displayName", displayName)
                .update();
    }

    private void insertCompanyAdmin(UUID userId) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_system_code, grant_reason,
                            granted_at, row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, 'COMPANY_ADMIN', 'COMPANY', :companyId, 'ACTIVE',
                            'SYSTEM', 'M107_TEST', 'test-fixture', transaction_timestamp(), 0,
                            transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("companyId", COMPANY_ID)
                .param("userId", userId)
                .update();
    }

    private void cleanUp() {
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE actor_user_id = :actorId OR target_id = :targetId")
                .param("actorId", ACTOR_ID)
                .param("targetId", TARGET_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id = :actorId")
                .param("actorId", ACTOR_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE user_id = :targetId")
                .param("targetId", TARGET_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE aggregate_id = :targetId")
                .param("targetId", TARGET_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.platform_role_assignment WHERE user_id IN (:actorId, :targetId)")
                .param("actorId", ACTOR_ID)
                .param("targetId", TARGET_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:actorId, :targetId)")
                .param("actorId", ACTOR_ID)
                .param("targetId", TARGET_ID)
                .update();
    }
}
