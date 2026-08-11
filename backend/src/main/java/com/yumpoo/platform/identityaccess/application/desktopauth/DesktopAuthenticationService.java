package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import com.yumpoo.platform.identityaccess.application.oauth.VerifiedWeComIdentity;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthAuthorization;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class DesktopAuthenticationService {

    public static final Duration AUTHORIZE_TTL = Duration.ofMinutes(5);
    public static final Duration HANDOFF_TTL = Duration.ofSeconds(60);

    private final WeComOAuthVerificationService weComOAuthService;
    private final DesktopAuthAttemptStore attemptStore;
    private final DesktopAuthTokenGenerator tokenGenerator;
    private final DesktopAuthTokenHasher tokenHasher;
    private final M015VerificationReceiptSigner receiptSigner;
    private final Clock clock;

    public DesktopAuthenticationService(
            WeComOAuthVerificationService weComOAuthService,
            DesktopAuthAttemptStore attemptStore,
            DesktopAuthTokenGenerator tokenGenerator,
            DesktopAuthTokenHasher tokenHasher,
            M015VerificationReceiptSigner receiptSigner,
            Clock clock
    ) {
        this.weComOAuthService = Objects.requireNonNull(
                weComOAuthService,
                "weComOAuthService must not be null"
        );
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore must not be null");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator must not be null");
        this.tokenHasher = Objects.requireNonNull(tokenHasher, "tokenHasher must not be null");
        this.receiptSigner = Objects.requireNonNull(receiptSigner, "receiptSigner must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public DesktopAuthorization begin(
            DesktopAuthToken desktopState,
            PkceS256Challenge pkceChallenge,
            String requestId
    ) {
        Objects.requireNonNull(desktopState, "desktopState must not be null");
        Objects.requireNonNull(pkceChallenge, "pkceChallenge must not be null");
        WeComOAuthAuthorization oauthAuthorization = weComOAuthService.begin(requestId);
        DesktopAuthToken oauthState = DesktopAuthToken.of(oauthAuthorization.state().value());
        Instant createdAt = oauthAuthorization.expiresAt().minus(AUTHORIZE_TTL);
        attemptStore.create(new DesktopAuthAttempt(
                tokenHasher.hash(desktopState),
                tokenHasher.hash(oauthState),
                pkceChallenge,
                requestId,
                createdAt,
                oauthAuthorization.expiresAt()
        ));
        return new DesktopAuthorization(
                oauthAuthorization.authorizationUri(),
                oauthAuthorization.nonce(),
                oauthAuthorization.expiresAt()
        );
    }

    public DesktopHandoffAuthorization completeAuthorization(
            String authorizationCode,
            OAuthAttemptToken oauthState,
            OAuthAttemptToken oauthNonce,
            DesktopAuthToken desktopState
    ) {
        Objects.requireNonNull(oauthState, "oauthState must not be null");
        Objects.requireNonNull(oauthNonce, "oauthNonce must not be null");
        Objects.requireNonNull(desktopState, "desktopState must not be null");

        VerifiedWeComIdentity identity = weComOAuthService.verify(
                authorizationCode,
                oauthState.value(),
                oauthNonce.value()
        );
        DesktopIdentityFingerprint fingerprint = receiptSigner.fingerprint(
                identity.corpId(),
                identity.memberId()
        );
        DesktopAuthToken handoffCode = tokenGenerator.generate();
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(HANDOFF_TTL);
        boolean issued = attemptStore.issueHandoff(
                tokenHasher.hash(DesktopAuthToken.of(oauthState.value())),
                tokenHasher.hash(desktopState),
                tokenHasher.hash(handoffCode),
                fingerprint,
                issuedAt,
                expiresAt
        );
        if (!issued) {
            throw authenticationRequired();
        }
        return new DesktopHandoffAuthorization(handoffCode, desktopState, expiresAt);
    }

    public M015VerificationReceipt exchange(
            DesktopAuthToken handoffCode,
            DesktopAuthToken desktopState,
            PkceVerifier pkceVerifier,
            String requestId
    ) {
        Objects.requireNonNull(handoffCode, "handoffCode must not be null");
        Objects.requireNonNull(desktopState, "desktopState must not be null");
        Objects.requireNonNull(pkceVerifier, "pkceVerifier must not be null");
        DesktopAuthExchange exchange = attemptStore.consume(
                        tokenHasher.hash(desktopState),
                        tokenHasher.hash(handoffCode),
                        pkceVerifier.challenge(),
                        clock.instant()
                )
                .orElseThrow(DesktopAuthenticationService::authenticationRequired);
        return receiptSigner.sign(exchange.identityFingerprint(), requestId);
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
