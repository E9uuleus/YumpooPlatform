package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttempt;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHash;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptTokenGenerator;
import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class WebAuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAuthenticationService.class);

    public static final Duration ATTEMPT_TTL = Duration.ofMinutes(5);
    private static final int MAX_AUTHORIZATION_CODE_LENGTH = 512;

    private final OAuthAttemptStore attemptStore;
    private final WebIdentityProvider identityProvider;
    private final OAuthAttemptTokenGenerator tokenGenerator;
    private final OAuthAttemptHasher attemptHasher;
    private final WebLoginCompletionService completionService;
    private final AuthenticationEventService eventService;
    private final Clock clock;

    public WebAuthenticationService(
            OAuthAttemptStore attemptStore,
            WebIdentityProvider identityProvider,
            OAuthAttemptTokenGenerator tokenGenerator,
            OAuthAttemptHasher attemptHasher,
            WebLoginCompletionService completionService,
            AuthenticationEventService eventService,
            Clock clock
    ) {
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore must not be null");
        this.identityProvider = Objects.requireNonNull(
                identityProvider,
                "identityProvider must not be null"
        );
        this.tokenGenerator = Objects.requireNonNull(
                tokenGenerator,
                "tokenGenerator must not be null"
        );
        this.attemptHasher = Objects.requireNonNull(attemptHasher, "attemptHasher must not be null");
        this.completionService = Objects.requireNonNull(
                completionService,
                "completionService must not be null"
        );
        this.eventService = Objects.requireNonNull(eventService, "eventService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public WebLoginAuthorization begin(String requestId) {
        OAuthAttemptToken state = tokenGenerator.generate();
        OAuthAttemptToken nonce = tokenGenerator.generate();
        OAuthAttemptHash stateHash = attemptHasher.hash(state);
        OAuthAttemptHash nonceHash = attemptHasher.hash(nonce);
        if (stateHash.equals(nonceHash)) {
            throw new IllegalStateException("OAuth token generator returned duplicate credentials");
        }

        URI authorizationUri;
        try {
            authorizationUri = Objects.requireNonNull(
                    identityProvider.buildAuthorizationUri(state.value()),
                    "authorization URI must not be null"
            );
        } catch (WeComAuthenticationFailedException exception) {
            ApplicationException mapped = authenticationRequired();
            recordRejected("AUTHORIZE", mapped);
            throw mapped;
        } catch (WeComDependencyUnavailableException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("AUTHORIZE", mapped);
            throw mapped;
        } catch (RuntimeException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("AUTHORIZE", mapped);
            throw mapped;
        }

        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(ATTEMPT_TTL);
        try {
            attemptStore.create(new OAuthAttempt(
                    stateHash,
                    nonceHash,
                    requestId,
                    createdAt,
                    expiresAt
            ));
        } catch (DataAccessResourceFailureException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("AUTHORIZE", mapped);
            throw mapped;
        }
        return new WebLoginAuthorization(authorizationUri, nonce, expiresAt);
    }

    public IssuedSession complete(String authorizationCode, String state, String nonce) {
        OAuthAttemptToken stateToken;
        OAuthAttemptToken nonceToken;
        try {
            stateToken = OAuthAttemptToken.of(state);
            nonceToken = OAuthAttemptToken.of(nonce);
        } catch (NullPointerException | IllegalArgumentException exception) {
            ApplicationException mapped = authenticationRequired();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        }
        boolean consumed;
        try {
            consumed = validAuthorizationCode(authorizationCode)
                    && attemptStore.consume(
                            attemptHasher.hash(stateToken),
                            attemptHasher.hash(nonceToken),
                            clock.instant()
                    );
        } catch (DataAccessResourceFailureException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        }
        if (!consumed) {
            ApplicationException mapped = authenticationRequired();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        }

        WeComMemberIdentity identity;
        try {
            identity = Objects.requireNonNull(
                    identityProvider.exchangeCode(authorizationCode),
                    "provider identity must not be null"
            );
        } catch (WeComAuthenticationFailedException exception) {
            ApplicationException mapped = authenticationRequired();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        } catch (WeComDependencyUnavailableException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        } catch (RuntimeException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        }

        if (!identityProvider.expectedCorpId().equals(identity.corpId())) {
            ApplicationException mapped = authenticationRequired();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        }
        try {
            return completionService.complete(identity.memberId());
        } catch (ApplicationException exception) {
            recordRejected("CALLBACK", exception);
            throw exception;
        } catch (DataAccessResourceFailureException exception) {
            ApplicationException mapped = dependencyUnavailable();
            recordRejected("CALLBACK", mapped);
            throw mapped;
        }
    }

    public int purgeExpiredAttempts(int batchSize, int maxBatches) {
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("purge limits must be positive");
        }
        int total = 0;
        Instant now = clock.instant();
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = attemptStore.purgeExpired(now, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    private void recordRejected(String stage, ApplicationException exception) {
        try {
            eventService.loginRejected(stage, exception.errorCode().name());
        } catch (RuntimeException auditFailure) {
            LOGGER.error("login rejection audit failed stage={} errorType={}",
                    stage, auditFailure.getClass().getSimpleName());
            // 登录拒绝追踪是尽力记录；不得用记录失败覆盖原始 401/503。
        }
    }

    private static boolean validAuthorizationCode(String authorizationCode) {
        return authorizationCode != null
                && !authorizationCode.isBlank()
                && authorizationCode.length() <= MAX_AUTHORIZATION_CODE_LENGTH;
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException dependencyUnavailable() {
        return new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
