package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttempt;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHash;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import com.yumpoo.platform.identityaccess.application.oauth.WeComIdentityGateway;
import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T02:00:00Z");
    private static final DesktopAuthToken DESKTOP_STATE = desktopToken('D');
    private static final OAuthAttemptToken OAUTH_STATE = oauthToken('O');
    private static final OAuthAttemptToken OAUTH_NONCE = oauthToken('N');
    private static final DesktopAuthToken HANDOFF_CODE = desktopToken('H');
    private static final PkceVerifier VERIFIER = PkceVerifier.of("V".repeat(43));

    private RecordingDesktopStore desktopStore;
    private DesktopAuthenticationService service;

    @BeforeEach
    void setUp() {
        desktopStore = new RecordingDesktopStore();
        RecordingOAuthStore oauthStore = new RecordingOAuthStore();
        List<OAuthAttemptToken> generated = new ArrayList<>(List.of(OAUTH_STATE, OAUTH_NONCE));
        WeComOAuthVerificationService oauthService = new WeComOAuthVerificationService(
                oauthStore,
                new WeComIdentityGateway() {
                    @Override
                    public URI buildAuthorizationUri(String state) {
                        return URI.create("https://open.weixin.qq.com/connect/oauth2/authorize?state=" + state);
                    }

                    @Override
                    public WeComMemberIdentity exchangeCode(String code) {
                        if (!"valid-code".equals(code)) {
                            throw new WeComAuthenticationFailedException();
                        }
                        return new WeComMemberIdentity("corp", "member");
                    }
                },
                () -> generated.removeFirst(),
                new OAuthAttemptHasher(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "corp",
                Set.of("member")
        );
        service = new DesktopAuthenticationService(
                oauthService,
                desktopStore,
                () -> HANDOFF_CODE,
                new DesktopAuthTokenHasher(),
                new M015VerificationReceiptSigner(
                        "m015-service-test-key-0123456789-abcdef!",
                        Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsFiveMinuteAttemptIssuesSixtySecondHandoffAndExchangesReceipt() {
        DesktopAuthorization authorization = service.begin(
                DESKTOP_STATE,
                VERIFIER.challenge(),
                "m015.service-1"
        );

        assertThat(authorization.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(desktopStore.created.createdAt()).isEqualTo(NOW);
        assertThat(desktopStore.created.authorizeExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(desktopStore.created.pkceChallenge()).isEqualTo(VERIFIER.challenge());

        DesktopHandoffAuthorization handoff = service.completeAuthorization(
                "valid-code",
                OAUTH_STATE,
                OAUTH_NONCE,
                DESKTOP_STATE
        );

        assertThat(handoff.handoffCode()).isEqualTo(HANDOFF_CODE);
        assertThat(handoff.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(desktopStore.issuedFingerprint.corpFingerprint()).hasSize(64);
        desktopStore.exchange = Optional.of(new DesktopAuthExchange(
                desktopStore.issuedFingerprint,
                NOW,
                NOW
        ));

        M015VerificationReceipt receipt = service.exchange(
                HANDOFF_CODE,
                DESKTOP_STATE,
                VERIFIER,
                "m015.exchange-1"
        );

        assertThat(receipt.status()).isEqualTo("PASS");
        assertThat(receipt.requestId()).isEqualTo("m015.exchange-1");
        assertThat(receipt.corpFingerprint())
                .isEqualTo(desktopStore.issuedFingerprint.corpFingerprint());
    }

    @Test
    void storeMismatchAndReplayAreUniformAuthenticationFailures() {
        desktopStore.issueResult = false;
        service.begin(DESKTOP_STATE, VERIFIER.challenge(), "m015.service-2");

        assertThatThrownBy(() -> service.completeAuthorization(
                "valid-code",
                OAUTH_STATE,
                OAUTH_NONCE,
                DESKTOP_STATE
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED)
        );
    }

    @Test
    void wrongProviderCodeIsAUniformAuthenticationFailure() {
        service.begin(DESKTOP_STATE, VERIFIER.challenge(), "m015.service-3");

        assertThatThrownBy(() -> service.completeAuthorization(
                "wrong-code",
                OAUTH_STATE,
                OAUTH_NONCE,
                DESKTOP_STATE
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED)
        );
    }

    @Test
    void wrongOauthStateIsAUniformAuthenticationFailure() {
        service.begin(DESKTOP_STATE, VERIFIER.challenge(), "m015.service-4");

        assertThatThrownBy(() -> service.completeAuthorization(
                "valid-code",
                oauthToken('X'),
                OAUTH_NONCE,
                DESKTOP_STATE
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED)
        );
    }

    private static DesktopAuthToken desktopToken(char value) {
        return DesktopAuthToken.of(String.valueOf(value).repeat(43));
    }

    private static OAuthAttemptToken oauthToken(char value) {
        return OAuthAttemptToken.of(String.valueOf(value).repeat(43));
    }

    private static final class RecordingOAuthStore implements OAuthAttemptStore {

        private OAuthAttempt attempt;

        @Override
        public void create(OAuthAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public boolean consume(OAuthAttemptHash stateHash, OAuthAttemptHash nonceHash, Instant consumedAt) {
            return attempt != null
                    && attempt.stateHash().equals(stateHash)
                    && attempt.nonceHash().equals(nonceHash)
                    && consumedAt.isBefore(attempt.expiresAt());
        }
    }

    private static final class RecordingDesktopStore implements DesktopAuthAttemptStore {

        private DesktopAuthAttempt created;
        private DesktopIdentityFingerprint issuedFingerprint;
        private boolean issueResult = true;
        private Optional<DesktopAuthExchange> exchange = Optional.empty();

        @Override
        public void create(DesktopAuthAttempt attempt) {
            created = attempt;
        }

        @Override
        public boolean issueHandoff(
                DesktopAuthTokenHash oauthStateHash,
                DesktopAuthTokenHash desktopStateHash,
                DesktopAuthTokenHash handoffCodeHash,
                DesktopIdentityFingerprint identityFingerprint,
                Instant issuedAt,
                Instant expiresAt
        ) {
            issuedFingerprint = identityFingerprint;
            return issueResult;
        }

        @Override
        public Optional<DesktopAuthExchange> consume(
                DesktopAuthTokenHash desktopStateHash,
                DesktopAuthTokenHash handoffCodeHash,
                PkceS256Challenge pkceChallenge,
                Instant consumedAt
        ) {
            return exchange;
        }
    }
}
