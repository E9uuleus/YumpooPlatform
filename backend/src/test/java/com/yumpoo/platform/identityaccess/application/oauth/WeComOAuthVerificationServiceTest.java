package com.yumpoo.platform.identityaccess.application.oauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComOAuthVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T01:02:03Z");
    private static final String CORP_ID = "corp-test";
    private static final String MEMBER_ID = "member-test";
    private static final OAuthAttemptToken STATE = token('A');
    private static final OAuthAttemptToken NONCE = token('B');
    private static final OAuthAttemptToken WRONG_NONCE = token('C');

    @Test
    void beginStoresOnlyHashesAndKeepsCredentialStringRepresentationsRedacted() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        FakeGateway gateway = new FakeGateway();
        OAuthAttemptHasher hasher = new OAuthAttemptHasher();
        WeComOAuthVerificationService service = service(store, gateway, fixedClock(), STATE, NONCE);

        WeComOAuthAuthorization authorization = service.begin("request-123");

        assertThat(gateway.authorizationState).isEqualTo(STATE.value());
        assertThat(store.attempt.stateHash()).isEqualTo(hasher.hash(STATE));
        assertThat(store.attempt.nonceHash()).isEqualTo(hasher.hash(NONCE));
        assertThat(store.attempt.requestId()).isEqualTo("request-123");
        assertThat(store.attempt.createdAt()).isEqualTo(NOW);
        assertThat(store.attempt.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(store.attempt.stateHash().value()).doesNotContain(STATE.value());
        assertThat(store.attempt.nonceHash().value()).doesNotContain(NONCE.value());
        assertThat(authorization.authorizationUri().toString()).contains(STATE.value());
        assertThat(authorization.toString())
                .doesNotContain(STATE.value())
                .doesNotContain(NONCE.value());
        assertThat(authorization.state().toString()).doesNotContain(STATE.value());
        assertThat(authorization.nonce().toString()).doesNotContain(NONCE.value());
    }

    @Test
    void verifyConsumesBeforeGatewayAndReturnsOnlyTheConfiguredInternalMember() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        FakeGateway gateway = new FakeGateway();
        WeComOAuthVerificationService service = service(store, gateway, fixedClock(), STATE, NONCE);
        WeComOAuthAuthorization authorization = service.begin("request-verify");
        gateway.exchangeAction = () -> {
            assertThat(store.consumed).isTrue();
            return new WeComMemberIdentity(CORP_ID, MEMBER_ID);
        };

        VerifiedWeComIdentity verified = service.verify(
                "one-time-code",
                authorization.state().value(),
                authorization.nonce().value()
        );

        assertThat(verified.corpId()).isEqualTo(CORP_ID);
        assertThat(verified.memberId()).isEqualTo(MEMBER_ID);
        assertThat(verified.toString())
                .doesNotContain(CORP_ID)
                .doesNotContain(MEMBER_ID);
        assertThat(gateway.exchangeCalls).isOne();
    }

    @Test
    void wrongNonceDoesNotConsumeTheStateOrCallWeCom() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        FakeGateway gateway = new FakeGateway();
        WeComOAuthVerificationService service = service(store, gateway, fixedClock(), STATE, NONCE);
        service.begin("request-wrong-nonce");

        assertAuthenticationRequired(() -> service.verify(
                "one-time-code",
                STATE.value(),
                WRONG_NONCE.value()
        ));

        assertThat(store.consumed).isFalse();
        assertThat(gateway.exchangeCalls).isZero();
    }

    @Test
    void expiredAttemptIsRejectedWithoutConsumption() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        FakeGateway gateway = new FakeGateway();
        MutableClock clock = new MutableClock(NOW);
        WeComOAuthVerificationService service = service(store, gateway, clock, STATE, NONCE);
        service.begin("request-expired");
        clock.set(NOW.plus(Duration.ofMinutes(5)));

        assertAuthenticationRequired(() -> service.verify(
                "one-time-code",
                STATE.value(),
                NONCE.value()
        ));

        assertThat(store.consumed).isFalse();
        assertThat(gateway.exchangeCalls).isZero();
    }

    @Test
    void invalidAndDependencyGatewayFailuresMapToStableApplicationErrorsAfterConsumption() {
        InMemoryAttemptStore invalidStore = new InMemoryAttemptStore();
        FakeGateway invalidGateway = new FakeGateway();
        WeComOAuthVerificationService invalidService = service(
                invalidStore,
                invalidGateway,
                fixedClock(),
                STATE,
                NONCE
        );
        invalidService.begin("request-invalid-code");
        invalidGateway.exchangeAction = () -> {
            throw new WeComAuthenticationFailedException();
        };

        assertAuthenticationRequired(() -> invalidService.verify(
                "invalid-code",
                STATE.value(),
                NONCE.value()
        ));
        assertThat(invalidStore.consumed).isTrue();

        InMemoryAttemptStore dependencyStore = new InMemoryAttemptStore();
        FakeGateway dependencyGateway = new FakeGateway();
        WeComOAuthVerificationService dependencyService = service(
                dependencyStore,
                dependencyGateway,
                fixedClock(),
                token('D'),
                token('E')
        );
        WeComOAuthAuthorization dependencyAuthorization = dependencyService.begin("request-dependency");
        dependencyGateway.exchangeAction = () -> {
            throw new WeComDependencyUnavailableException();
        };

        assertThatThrownBy(() -> dependencyService.verify(
                "unavailable-code",
                dependencyAuthorization.state().value(),
                dependencyAuthorization.nonce().value()
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE)
        );
        assertThat(dependencyStore.consumed).isTrue();
    }

    @Test
    void corpAndMemberAllowlistMismatchesAreIndistinguishableAuthenticationFailures() {
        assertIdentityRejected(new WeComMemberIdentity("other-corp", MEMBER_ID), token('F'), token('G'));
        assertIdentityRejected(new WeComMemberIdentity(CORP_ID, "other-member"), token('H'), token('I'));
    }

    @Test
    void malformedCallbackInputsAreRejectedBeforeAttemptConsumption() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        FakeGateway gateway = new FakeGateway();
        WeComOAuthVerificationService service = service(store, gateway, fixedClock(), STATE, NONCE);
        service.begin("request-malformed");

        assertAuthenticationRequired(() -> service.verify(null, STATE.value(), NONCE.value()));
        assertAuthenticationRequired(() -> service.verify("code", "forged", NONCE.value()));
        assertAuthenticationRequired(() -> service.verify("code", STATE.value(), null));

        assertThat(store.consumeCalls).isZero();
        assertThat(gateway.exchangeCalls).isZero();
    }

    @Test
    void secureGeneratorProducesDistinct256BitBase64UrlTokensAndHasherIsDeterministic() {
        SecureOAuthAttemptTokenGenerator generator = new SecureOAuthAttemptTokenGenerator();
        OAuthAttemptHasher hasher = new OAuthAttemptHasher();

        OAuthAttemptToken first = generator.generate();
        OAuthAttemptToken second = generator.generate();

        assertThat(first.value()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(second.value()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).isNotEqualTo(first);
        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(first));
        assertThat(hasher.hash(second)).isNotEqualTo(hasher.hash(first));
        assertThat(hasher.hash(first).value()).matches("[0-9a-f]{64}");
    }

    private void assertIdentityRejected(
            WeComMemberIdentity gatewayIdentity,
            OAuthAttemptToken state,
            OAuthAttemptToken nonce
    ) {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        FakeGateway gateway = new FakeGateway();
        gateway.exchangeAction = () -> gatewayIdentity;
        WeComOAuthVerificationService service = service(store, gateway, fixedClock(), state, nonce);
        service.begin("request-identity-mismatch");

        assertAuthenticationRequired(() -> service.verify(
                "one-time-code",
                state.value(),
                nonce.value()
        ));
        assertThat(store.consumed).isTrue();
    }

    private static WeComOAuthVerificationService service(
            InMemoryAttemptStore store,
            FakeGateway gateway,
            Clock clock,
            OAuthAttemptToken state,
            OAuthAttemptToken nonce
    ) {
        Deque<OAuthAttemptToken> tokens = new ArrayDeque<>();
        tokens.add(state);
        tokens.add(nonce);
        return new WeComOAuthVerificationService(
                store,
                gateway,
                tokens::removeFirst,
                new OAuthAttemptHasher(),
                clock,
                CORP_ID,
                Set.of(MEMBER_ID)
        );
    }

    private static void assertAuthenticationRequired(Runnable callback) {
        assertThatThrownBy(callback::run)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED)
                );
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static OAuthAttemptToken token(char character) {
        return OAuthAttemptToken.of(String.valueOf(character).repeat(43));
    }

    private static final class InMemoryAttemptStore implements OAuthAttemptStore {

        private OAuthAttempt attempt;
        private boolean consumed;
        private int consumeCalls;

        @Override
        public void create(OAuthAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public boolean consume(
                OAuthAttemptHash stateHash,
                OAuthAttemptHash nonceHash,
                Instant consumedAt
        ) {
            consumeCalls++;
            if (attempt == null
                    || consumed
                    || !attempt.stateHash().equals(stateHash)
                    || !attempt.nonceHash().equals(nonceHash)
                    || consumedAt.isBefore(attempt.createdAt())
                    || !consumedAt.isBefore(attempt.expiresAt())) {
                return false;
            }
            consumed = true;
            return true;
        }
    }

    private static final class FakeGateway implements WeComIdentityGateway {

        private String authorizationState;
        private int exchangeCalls;
        private ExchangeAction exchangeAction = () -> new WeComMemberIdentity(CORP_ID, MEMBER_ID);

        @Override
        public URI buildAuthorizationUri(String state) {
            authorizationState = state;
            return URI.create("https://open.weixin.qq.com/connect/oauth2/authorize?state=" + state);
        }

        @Override
        public WeComMemberIdentity exchangeCode(String code) {
            exchangeCalls++;
            return exchangeAction.exchange();
        }
    }

    @FunctionalInterface
    private interface ExchangeAction {

        WeComMemberIdentity exchange();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
