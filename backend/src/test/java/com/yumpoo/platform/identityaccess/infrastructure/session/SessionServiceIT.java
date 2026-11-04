package com.yumpoo.platform.identityaccess.infrastructure.session;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionServiceIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID USER_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000003"
    );

    @Autowired
    private SessionService service;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        deleteTestUser();
        insertUser("ACTIVE", "ENABLED");
    }

    @AfterEach
    void tearDown() {
        deleteTestUser();
    }

    private void deleteTestUser() {
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
    }

    @Test
    void issueAuthenticateRotateAndAuthorizationInvalidationAreAtomic() {
        var issued = service.issueWebSession(USER_ID, "web-test");

        assertThat(service.authenticate(issued.sessionCredential()).user().userId())
                .isEqualTo(USER_ID);
        assertThat(databaseCredentialMaterial())
                .doesNotContain(issued.sessionCredential().value(), issued.csrfCredential().value());

        var rotated = service.rotate(issued.sessionCredential());
        assertError(issued.sessionCredential(), StandardErrorCode.AUTHENTICATION_REQUIRED);
        assertThat(service.authenticate(rotated.sessionCredential()).session().id())
                .isEqualTo(rotated.session().id());

        service.incrementAuthorizationVersion(
                USER_ID,
                SessionRevocationReason.AUTHORIZATION_CHANGED
        );
        assertError(rotated.sessionCredential(), StandardErrorCode.AUTHENTICATION_REQUIRED);
        assertThat(jdbcClient.sql("""
                        SELECT authorization_version
                        FROM yumpoo.identity_user
                        WHERE id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void concurrentCsrfRepairConvergesOnOneSessionBoundCredential() throws Exception {
        var issued = service.issueWebSession(USER_ID, "csrf-repair-it");
        var authenticated = service.authenticate(issued.sessionCredential());
        int concurrency = 12;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(concurrency);
        List<Future<String>> repairs = new ArrayList<>();
        try {
            for (int index = 0; index < concurrency; index++) {
                repairs.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.repairCsrf(authenticated).value();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<String> credentials = new HashSet<>();
            for (Future<String> repair : repairs) {
                credentials.add(repair.get(10, TimeUnit.SECONDS));
            }

            assertThat(credentials).hasSize(1);
            var repaired = new com.yumpoo.platform.identityaccess.application.session.SessionCredential(
                    credentials.iterator().next()
            );
            assertThat(service.verifyCsrf(
                    service.authenticate(issued.sessionCredential()),
                    repaired
            )).isTrue();
            assertThat(databaseCredentialMaterial()).doesNotContain(repaired.value());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void disabledUserIsRecognizableAsAccountDisabled() {
        var issued = service.issueWebSession(USER_ID, null);
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET account_status = 'DISABLED',
                            account_disabled_at = transaction_timestamp(),
                            account_disabled_by_user_id = id,
                            account_disabled_reason = 'SECURITY_REVIEW',
                            authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE id = :userId
                        """)
                .param("userId", USER_ID)
                .update();

        assertError(issued.sessionCredential(), StandardErrorCode.ACCOUNT_DISABLED);
        assertThat(jdbcClient.sql("""
                        SELECT revoke_reason
                        FROM yumpoo.login_session
                        WHERE id = :sessionId
                        """)
                .param("sessionId", issued.session().id())
                .query(String.class)
                .single()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void userLogoutCanBeRecognizedAndRetriedWithoutReactivatingTheSession() {
        var issued = service.issueWebSession(USER_ID, null);
        var authenticated = service.authenticateForLogout(issued.sessionCredential());

        assertThat(service.logout(authenticated)).isTrue();
        assertError(issued.sessionCredential(), StandardErrorCode.AUTHENTICATION_REQUIRED);

        var retry = service.authenticateForLogout(issued.sessionCredential());
        assertThat(service.logout(retry)).isFalse();
        assertThat(jdbcClient.sql("""
                        SELECT status || '|' || revoke_reason
                        FROM yumpoo.login_session
                        WHERE id = :sessionId
                        """)
                .param("sessionId", issued.session().id())
                .query(String.class)
                .single()).isEqualTo("REVOKED|USER_LOGOUT");
    }

    @Test
    void concurrentRotationAllowsExactlyOneSuccess() throws Exception {
        var issued = service.issueWebSession(USER_ID, "rotation-race");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> rotateAfterBarrier(issued, ready, start)),
                    executor.submit(() -> rotateAfterBarrier(issued, ready, start))
            );
            ready.await();
            start.countDown();

            assertThat(attempts)
                    .extracting(future -> future.get())
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.login_session
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", USER_ID)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    @Transactional
    void profileRefreshCanIncreaseRowVersionWithoutChangingAuthorizationVersion() {
        long before = authorizationVersion();
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET display_name = 'Updated Name',
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE id = :userId
                        """)
                .param("userId", USER_ID)
                .update();

        assertThat(authorizationVersion()).isEqualTo(before);
    }

    @Test
    void cleanupPurgesAnUntouchedActiveSessionAtPurgeBoundary() {
        var issued = service.issueWebSession(USER_ID, "abandoned-session");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        jdbcClient.sql("""
                        UPDATE yumpoo.login_session
                        SET issued_at = :issuedAt,
                            last_seen_at = :lastSeenAt,
                            idle_expires_at = :idleExpiresAt,
                            absolute_expires_at = :absoluteExpiresAt,
                            purge_after = :purgeAfter
                        WHERE id = :sessionId
                        """)
                .param("issuedAt", now.minusDays(8))
                .param("lastSeenAt", now.minusDays(8))
                .param("idleExpiresAt", now.minusDays(7))
                .param("absoluteExpiresAt", now.minusHours(24))
                .param("purgeAfter", now)
                .param("sessionId", issued.session().id())
                .update();

        assertThat(service.purgeDueSessions()).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.login_session WHERE id = :sessionId
                        """)
                .param("sessionId", issued.session().id())
                .query(Integer.class)
                .single()).isZero();
    }

    private void assertError(
            com.yumpoo.platform.identityaccess.application.session.SessionCredential credential,
            StandardErrorCode expected
    ) {
        assertThatThrownBy(() -> service.authenticate(credential))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private boolean rotateAfterBarrier(
            com.yumpoo.platform.identityaccess.application.session.IssuedSession issued,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.rotate(issued.sessionCredential());
            return true;
        } catch (ApplicationException exception) {
            assertThat(exception.errorCode())
                    .isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED);
            return false;
        }
    }

    private String databaseCredentialMaterial() {
        return jdbcClient.sql("""
                        SELECT session_token_fingerprint || '|' || csrf_token_fingerprint
                        FROM yumpoo.login_session
                        WHERE user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(String.class)
                .single();
    }

    private long authorizationVersion() {
        return jdbcClient.sql("""
                        SELECT authorization_version
                        FROM yumpoo.identity_user
                        WHERE id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Long.class)
                .single();
    }

    private void insertUser(String employment, String account) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, :employment, :account,
                            'Session Test User', transaction_timestamp(),
                            0, 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("userId", USER_ID)
                .param("companyId", COMPANY_ID)
                .param("employment", employment)
                .param("account", account)
                .update();
    }
}
