package com.yumpoo.platform.identityaccess.application.oauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * M0-12 诊断流程编排。消费 attempt 的提交发生在访问企微之前，外部失败不会恢复 attempt。
 */
public final class WeComOAuthVerificationService {

    public static final Duration DEFAULT_ATTEMPT_TTL = Duration.ofMinutes(5);
    private static final int MAX_AUTHORIZATION_CODE_LENGTH = 512;

    private final OAuthAttemptStore attemptStore;
    private final WeComIdentityGateway identityGateway;
    private final OAuthAttemptTokenGenerator tokenGenerator;
    private final OAuthAttemptHasher attemptHasher;
    private final Clock clock;
    private final Duration attemptTtl;
    private final String expectedCorpId;
    private final Set<String> allowedMemberIds;

    public WeComOAuthVerificationService(
            OAuthAttemptStore attemptStore,
            WeComIdentityGateway identityGateway,
            OAuthAttemptTokenGenerator tokenGenerator,
            OAuthAttemptHasher attemptHasher,
            Clock clock,
            String expectedCorpId,
            Set<String> allowedMemberIds
    ) {
        this(
                attemptStore,
                identityGateway,
                tokenGenerator,
                attemptHasher,
                clock,
                DEFAULT_ATTEMPT_TTL,
                expectedCorpId,
                allowedMemberIds
        );
    }

    public WeComOAuthVerificationService(
            OAuthAttemptStore attemptStore,
            WeComIdentityGateway identityGateway,
            OAuthAttemptTokenGenerator tokenGenerator,
            OAuthAttemptHasher attemptHasher,
            Clock clock,
            Duration attemptTtl,
            String expectedCorpId,
            Set<String> allowedMemberIds
    ) {
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore must not be null");
        this.identityGateway = Objects.requireNonNull(identityGateway, "identityGateway must not be null");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator must not be null");
        this.attemptHasher = Objects.requireNonNull(attemptHasher, "attemptHasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.attemptTtl = requirePositiveTtl(attemptTtl);
        this.expectedCorpId = requireIdentifier(expectedCorpId, "expectedCorpId");
        this.allowedMemberIds = copyAllowedMembers(allowedMemberIds);
    }

    public WeComOAuthAuthorization begin(String requestId) {
        OAuthAttemptToken state = tokenGenerator.generate();
        OAuthAttemptToken nonce = tokenGenerator.generate();
        OAuthAttemptHash stateHash = attemptHasher.hash(state);
        OAuthAttemptHash nonceHash = attemptHasher.hash(nonce);
        if (stateHash.equals(nonceHash)) {
            throw new IllegalStateException("OAuth token generator returned duplicate credentials");
        }

        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(attemptTtl);
        URI authorizationUri;
        try {
            authorizationUri = Objects.requireNonNull(
                    identityGateway.buildAuthorizationUri(state.value()),
                    "authorization URI must not be null"
            );
        } catch (WeComAuthenticationFailedException exception) {
            throw authenticationRequired();
        } catch (WeComDependencyUnavailableException exception) {
            throw dependencyUnavailable();
        } catch (RuntimeException exception) {
            throw dependencyUnavailable();
        }

        attemptStore.create(new OAuthAttempt(
                stateHash,
                nonceHash,
                requestId,
                createdAt,
                expiresAt
        ));
        return new WeComOAuthAuthorization(authorizationUri, state, nonce, expiresAt);
    }

    public VerifiedWeComIdentity verify(
            String authorizationCode,
            String state,
            String nonce
    ) {
        OAuthAttemptToken stateToken = parseCallbackToken(state);
        OAuthAttemptToken nonceToken = parseCallbackToken(nonce);
        if (!validAuthorizationCode(authorizationCode)) {
            throw authenticationRequired();
        }

        boolean consumed = attemptStore.consume(
                attemptHasher.hash(stateToken),
                attemptHasher.hash(nonceToken),
                clock.instant()
        );
        if (!consumed) {
            throw authenticationRequired();
        }

        WeComMemberIdentity identity;
        try {
            identity = Objects.requireNonNull(
                    identityGateway.exchangeCode(authorizationCode),
                    "WeCom identity must not be null"
            );
        } catch (WeComAuthenticationFailedException exception) {
            throw authenticationRequired();
        } catch (WeComDependencyUnavailableException exception) {
            throw dependencyUnavailable();
        } catch (RuntimeException exception) {
            throw dependencyUnavailable();
        }

        if (!expectedCorpId.equals(identity.corpId())
                || !allowedMemberIds.contains(identity.memberId())) {
            throw authenticationRequired();
        }
        return new VerifiedWeComIdentity(identity.corpId(), identity.memberId());
    }

    private static OAuthAttemptToken parseCallbackToken(String value) {
        try {
            return OAuthAttemptToken.of(value);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw authenticationRequired();
        }
    }

    private static boolean validAuthorizationCode(String authorizationCode) {
        return authorizationCode != null
                && !authorizationCode.isBlank()
                && authorizationCode.length() <= MAX_AUTHORIZATION_CODE_LENGTH;
    }

    private static Duration requirePositiveTtl(Duration attemptTtl) {
        Objects.requireNonNull(attemptTtl, "attemptTtl must not be null");
        if (attemptTtl.isZero() || attemptTtl.isNegative()) {
            throw new IllegalArgumentException("attemptTtl must be positive");
        }
        return attemptTtl;
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be between 1 and 256 characters");
        }
        return value;
    }

    private static Set<String> copyAllowedMembers(Set<String> memberIds) {
        Objects.requireNonNull(memberIds, "allowedMemberIds must not be null");
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("allowedMemberIds must not be empty");
        }
        for (String memberId : memberIds) {
            requireIdentifier(memberId, "allowed member ID");
        }
        return Set.copyOf(memberIds);
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException dependencyUnavailable() {
        return new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
