package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record DesktopAuthorization(
        URI authorizationUri,
        OAuthAttemptToken oauthNonce,
        Instant expiresAt
) {

    public DesktopAuthorization {
        Objects.requireNonNull(authorizationUri, "authorizationUri must not be null");
        Objects.requireNonNull(oauthNonce, "oauthNonce must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "DesktopAuthorization[expiresAt=" + expiresAt + ", credentials=REDACTED]";
    }
}
