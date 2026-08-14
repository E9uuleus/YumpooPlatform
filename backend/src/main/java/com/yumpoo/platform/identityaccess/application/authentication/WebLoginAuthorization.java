package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record WebLoginAuthorization(
        URI authorizationUri,
        OAuthAttemptToken nonce,
        Instant expiresAt
) {

    public WebLoginAuthorization {
        Objects.requireNonNull(authorizationUri, "authorizationUri must not be null");
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
