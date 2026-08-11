package com.yumpoo.platform.identityaccess.infrastructure.desktopauth;

import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthAttempt;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthExchange;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthToken;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthTokenHash;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthTokenHasher;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopIdentityFingerprint;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceS256Challenge;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceVerifier;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcDesktopAuthAttemptRepositoryIT {

    private static final Instant CREATED_AT = Instant.parse("2026-08-11T03:00:00Z");
    private static final DesktopAuthToken DESKTOP_STATE = token('D');
    private static final DesktopAuthToken OAUTH_STATE = token('O');
    private static final DesktopAuthToken HANDOFF_CODE = token('H');
    private static final DesktopAuthToken WRONG_TOKEN = token('W');
    private static final PkceVerifier VERIFIER = PkceVerifier.of("V".repeat(43));
    private static final PkceVerifier WRONG_VERIFIER = PkceVerifier.of("X".repeat(43));
    private static final DesktopIdentityFingerprint FINGERPRINT = new DesktopIdentityFingerprint(
            "a".repeat(64),
            "b".repeat(64)
    );
    private static final DesktopAuthTokenHasher HASHER = new DesktopAuthTokenHasher();

    @Autowired
    private DesktopAuthAttemptStore attemptStore;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetAttempts() {
        jdbcClient.sql("TRUNCATE TABLE yumpoo.desktop_auth_attempt").update();
    }

    @Test
    void wrongStateCodeAndPkceDoNotConsumeAndCorrectExchangeWinsOnce() {
        createAndIssue();
        Instant exchangeAt = CREATED_AT.plusSeconds(2);

        assertThat(attemptStore.consume(
                hash(WRONG_TOKEN), hash(HANDOFF_CODE), VERIFIER.challenge(), exchangeAt
        )).isEmpty();
        assertThat(attemptStore.consume(
                hash(DESKTOP_STATE), hash(WRONG_TOKEN), VERIFIER.challenge(), exchangeAt
        )).isEmpty();
        assertThat(attemptStore.consume(
                hash(DESKTOP_STATE), hash(HANDOFF_CODE), WRONG_VERIFIER.challenge(), exchangeAt
        )).isEmpty();

        Optional<DesktopAuthExchange> success = attemptStore.consume(
                hash(DESKTOP_STATE),
                hash(HANDOFF_CODE),
                VERIFIER.challenge(),
                exchangeAt
        );

        assertThat(success).hasValueSatisfying(exchange -> {
            assertThat(exchange.identityFingerprint()).isEqualTo(FINGERPRINT);
            assertThat(exchange.handoffIssuedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
            assertThat(exchange.consumedAt()).isEqualTo(exchangeAt);
        });
        assertThat(attemptStore.consume(
                hash(DESKTOP_STATE), hash(HANDOFF_CODE), VERIFIER.challenge(), exchangeAt.plusSeconds(1)
        )).isEmpty();
    }

    @Test
    void authorizeAndHandoffExpiryAreExclusiveAndLeaveAttemptUnconsumed() {
        attemptStore.create(attempt());

        assertThat(attemptStore.issueHandoff(
                hash(OAUTH_STATE),
                hash(DESKTOP_STATE),
                hash(HANDOFF_CODE),
                FINGERPRINT,
                CREATED_AT.plusSeconds(300),
                CREATED_AT.plusSeconds(360)
        )).isFalse();

        createAndIssueAfterReset();
        assertThat(attemptStore.consume(
                hash(DESKTOP_STATE),
                hash(HANDOFF_CODE),
                VERIFIER.challenge(),
                CREATED_AT.plusSeconds(61)
        )).isEmpty();
        assertThat(consumedAt()).isNull();
    }

    @Test
    void concurrentExchangeUsesOneUpdateReturningAndHasExactlyOneWinner() throws Exception {
        createAndIssue();
        int callers = 20;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<DesktopAuthExchange>>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent exchange start timed out");
                    }
                    return attemptStore.consume(
                            hash(DESKTOP_STATE),
                            hash(HANDOFF_CODE),
                            VERIFIER.challenge(),
                            CREATED_AT.plusSeconds(2)
                    );
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (Future<Optional<DesktopAuthExchange>> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).isPresent()) {
                    successes++;
                }
            }
            assertThat(successes).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databasePersistsOnlyHashesPkceAndHmacFingerprints() {
        createAndIssue();

        List<String> columns = jdbcClient.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'yumpoo'
                          AND table_name = 'desktop_auth_attempt'
                        ORDER BY ordinal_position
                        """)
                .query(String.class)
                .list();
        StoredValues stored = jdbcClient.sql("""
                        SELECT desktop_state_hash,
                               oauth_state_hash,
                               handoff_code_hash,
                               corp_fingerprint,
                               member_fingerprint
                        FROM yumpoo.desktop_auth_attempt
                        """)
                .query((resultSet, rowNumber) -> new StoredValues(
                        resultSet.getString("desktop_state_hash"),
                        resultSet.getString("oauth_state_hash"),
                        resultSet.getString("handoff_code_hash"),
                        resultSet.getString("corp_fingerprint"),
                        resultSet.getString("member_fingerprint")
                ))
                .single();

        assertThat(columns).containsExactly(
                "desktop_state_hash",
                "oauth_state_hash",
                "pkce_s256_challenge",
                "request_id",
                "created_at",
                "authorize_expires_at",
                "handoff_code_hash",
                "corp_fingerprint",
                "member_fingerprint",
                "handoff_issued_at",
                "handoff_expires_at",
                "consumed_at"
        );
        assertThat(stored.desktopStateHash()).isEqualTo(hash(DESKTOP_STATE).value());
        assertThat(stored.oauthStateHash()).isEqualTo(hash(OAUTH_STATE).value());
        assertThat(stored.handoffCodeHash()).isEqualTo(hash(HANDOFF_CODE).value());
        assertThat(stored.corpFingerprint()).isEqualTo(FINGERPRINT.corpFingerprint());
        assertThat(stored.memberFingerprint()).isEqualTo(FINGERPRINT.memberFingerprint());
        assertThat(stored.toString())
                .doesNotContain(DESKTOP_STATE.value(), OAUTH_STATE.value(), HANDOFF_CODE.value());
    }

    private void createAndIssueAfterReset() {
        jdbcClient.sql("TRUNCATE TABLE yumpoo.desktop_auth_attempt").update();
        createAndIssue();
    }

    private void createAndIssue() {
        attemptStore.create(attempt());
        assertThat(attemptStore.issueHandoff(
                hash(OAUTH_STATE),
                hash(DESKTOP_STATE),
                hash(HANDOFF_CODE),
                FINGERPRINT,
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(61)
        )).isTrue();
    }

    private static DesktopAuthAttempt attempt() {
        return new DesktopAuthAttempt(
                hash(DESKTOP_STATE),
                hash(OAUTH_STATE),
                VERIFIER.challenge(),
                "m015.persistence-1",
                CREATED_AT,
                CREATED_AT.plusSeconds(300)
        );
    }

    private Instant consumedAt() {
        return jdbcClient.sql("""
                        SELECT consumed_at
                        FROM yumpoo.desktop_auth_attempt
                        WHERE desktop_state_hash = :desktopStateHash
                        """)
                .param("desktopStateHash", hash(DESKTOP_STATE).value())
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private static DesktopAuthTokenHash hash(DesktopAuthToken token) {
        return HASHER.hash(token);
    }

    private static DesktopAuthToken token(char value) {
        return DesktopAuthToken.of(String.valueOf(value).repeat(43));
    }

    private record StoredValues(
            String desktopStateHash,
            String oauthStateHash,
            String handoffCodeHash,
            String corpFingerprint,
            String memberFingerprint
    ) {
    }
}
