package com.yumpoo.platform.identityaccess.application.oauth;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public final class WeComOAuthAuthorization {

    private final URI authorizationUri;
    private final OAuthAttemptToken state;
    private final OAuthAttemptToken nonce;
    private final Instant expiresAt;

    public WeComOAuthAuthorization(
            URI authorizationUri,
            OAuthAttemptToken state,
            OAuthAttemptToken nonce,
            Instant expiresAt
    ) {
        this.authorizationUri = Objects.requireNonNull(
                authorizationUri,
                "authorizationUri must not be null"
        );
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.nonce = Objects.requireNonNull(nonce, "nonce must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public URI authorizationUri() {
        return authorizationUri;
    }

    public OAuthAttemptToken state() {
        return state;
    }

    public OAuthAttemptToken nonce() {
        return nonce;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "WeComOAuthAuthorization[expiresAt=" + expiresAt + ", credentials=REDACTED]";
    }
}
