package com.yumpoo.platform.identityaccess.infrastructure.oauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttempt;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHash;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComIdentityGateway;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthAuthorization;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcOAuthAttemptRepositoryIT {

    private static final Instant CREATED_AT = Instant.parse("2026-08-10T02:00:00Z");
    private static final OAuthAttemptToken STATE = token('J');
    private static final OAuthAttemptToken NONCE = token('K');
    private static final OAuthAttemptToken WRONG_NONCE = token('L');
    private static final OAuthAttemptHasher HASHER = new OAuthAttemptHasher();

    @Autowired
    private OAuthAttemptStore attemptStore;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetAttempts() {
        jdbcClient.sql("TRUNCATE TABLE yumpoo.wecom_oauth_attempt").update();
    }

    @Test
    void wrongNonceDoesNotConsumeAndTheCorrectNonceCanStillWin() {
        attemptStore.create(attempt(STATE, NONCE, CREATED_AT.plus(Duration.ofMinutes(5))));

        boolean wrong = attemptStore.consume(
                HASHER.hash(STATE),
                HASHER.hash(WRONG_NONCE),
                CREATED_AT.plusSeconds(1)
        );

        assertThat(wrong).isFalse();
        assertThat(consumedAt()).isNull();
        assertThat(attemptStore.consume(
                HASHER.hash(STATE),
                HASHER.hash(NONCE),
                CREATED_AT.plusSeconds(2)
        )).isTrue();
        assertThat(consumedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
    }

    @Test
    void expiredAttemptIsRejectedWithoutBeingMarkedConsumed() {
        Instant expiresAt = CREATED_AT.plus(Duration.ofMinutes(5));
        attemptStore.create(attempt(STATE, NONCE, expiresAt));

        assertThat(attemptStore.consume(
                HASHER.hash(STATE),
                HASHER.hash(NONCE),
                expiresAt
        )).isFalse();
        assertThat(consumedAt()).isNull();
    }

    @Test
    void concurrentCallbacksHaveExactlyOneWinnerAndAllReplaysFail() throws Exception {
        attemptStore.create(attempt(STATE, NONCE, CREATED_AT.plus(Duration.ofMinutes(5))));
        int callers = 20;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent callback start timed out");
                    }
                    return attemptStore.consume(
                            HASHER.hash(STATE),
                            HASHER.hash(NONCE),
                            CREATED_AT.plusSeconds(1)
                    );
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnlyOnce(true);
            assertThat(outcomes.stream().filter(Boolean::booleanValue)).hasSize(1);
            assertThat(attemptStore.consume(
                    HASHER.hash(STATE),
                    HASHER.hash(NONCE),
                    CREATED_AT.plusSeconds(2)
            )).isFalse();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void consumptionSurvivesNewRepositoryAndServiceInstances() {
        attemptStore.create(attempt(STATE, NONCE, CREATED_AT.plus(Duration.ofMinutes(5))));

        JdbcOAuthAttemptRepository restartedRepository = new JdbcOAuthAttemptRepository(jdbcClient);
        assertThat(restartedRepository.consume(
                HASHER.hash(STATE),
                HASHER.hash(NONCE),
                CREATED_AT.plusSeconds(1)
        )).isTrue();

        JdbcOAuthAttemptRepository secondRestart = new JdbcOAuthAttemptRepository(jdbcClient);
        assertThat(secondRestart.consume(
                HASHER.hash(STATE),
                HASHER.hash(NONCE),
                CREATED_AT.plusSeconds(2)
        )).isFalse();
    }

    @Test
    void gatewayFailureAfterConsumptionCannotRestoreOrReplayTheAttempt() {
        AtomicInteger gatewayCalls = new AtomicInteger();
        WeComIdentityGateway unavailableGateway = new WeComIdentityGateway() {
            @Override
            public URI buildAuthorizationUri(String state) {
                return URI.create("https://open.weixin.qq.com/connect/oauth2/authorize?state=" + state);
            }

            @Override
            public WeComMemberIdentity exchangeCode(String code) {
                gatewayCalls.incrementAndGet();
                throw new WeComDependencyUnavailableException();
            }
        };
        Clock clock = Clock.fixed(CREATED_AT, ZoneOffset.UTC);
        WeComOAuthVerificationService firstProcess = service(unavailableGateway, clock);
        WeComOAuthAuthorization authorization = firstProcess.begin("request-gateway-failure");

        assertThatThrownBy(() -> firstProcess.verify(
                "one-time-code",
                authorization.state().value(),
                authorization.nonce().value()
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE)
        );
        assertThat(consumedAt()).isEqualTo(CREATED_AT);

        WeComOAuthVerificationService restartedProcess = service(unavailableGateway, clock);
        assertThatThrownBy(() -> restartedProcess.verify(
                "one-time-code",
                authorization.state().value(),
                authorization.nonce().value()
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED)
        );
        assertThat(gatewayCalls).hasValue(1);
    }

    @Test
    void onlyHashesAndLifecycleMetadataArePersisted() {
        attemptStore.create(attempt(STATE, NONCE, CREATED_AT.plus(Duration.ofMinutes(5))));

        List<String> columns = jdbcClient.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'yumpoo'
                          AND table_name = 'wecom_oauth_attempt'
                        ORDER BY ordinal_position
                        """)
                .query(String.class)
                .list();
        String storedState = jdbcClient.sql("""
                        SELECT state_hash
                        FROM yumpoo.wecom_oauth_attempt
                        """)
                .query(String.class)
                .single();
        String storedNonce = jdbcClient.sql("""
                        SELECT nonce_hash
                        FROM yumpoo.wecom_oauth_attempt
                        """)
                .query(String.class)
                .single();

        assertThat(columns).containsExactly(
                "state_hash",
                "nonce_hash",
                "request_id",
                "created_at",
                "expires_at",
                "consumed_at"
        );
        assertThat(storedState).isEqualTo(HASHER.hash(STATE).value()).doesNotContain(STATE.value());
        assertThat(storedNonce).isEqualTo(HASHER.hash(NONCE).value()).doesNotContain(NONCE.value());
    }

    private WeComOAuthVerificationService service(WeComIdentityGateway gateway, Clock clock) {
        List<OAuthAttemptToken> generatedTokens = new ArrayList<>(List.of(STATE, NONCE));
        return new WeComOAuthVerificationService(
                attemptStore,
                gateway,
                () -> generatedTokens.removeFirst(),
                HASHER,
                clock,
                "corp-test",
                Set.of("member-test")
        );
    }

    private static OAuthAttempt attempt(
            OAuthAttemptToken state,
            OAuthAttemptToken nonce,
            Instant expiresAt
    ) {
        return new OAuthAttempt(
                HASHER.hash(state),
                HASHER.hash(nonce),
                "request-persistence",
                CREATED_AT,
                expiresAt
        );
    }

    private Instant consumedAt() {
        return jdbcClient.sql("""
                        SELECT consumed_at
                        FROM yumpoo.wecom_oauth_attempt
                        WHERE state_hash = :stateHash
                        """)
                .param("stateHash", HASHER.hash(STATE).value())
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private static OAuthAttemptToken token(char character) {
        return OAuthAttemptToken.of(String.valueOf(character).repeat(43));
    }
}
