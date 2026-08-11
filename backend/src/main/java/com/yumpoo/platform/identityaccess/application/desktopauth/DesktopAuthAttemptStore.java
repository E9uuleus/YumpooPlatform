package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.time.Instant;
import java.util.Optional;

public interface DesktopAuthAttemptStore {

    void create(DesktopAuthAttempt attempt);

    boolean issueHandoff(
            DesktopAuthTokenHash oauthStateHash,
            DesktopAuthTokenHash desktopStateHash,
            DesktopAuthTokenHash handoffCodeHash,
            DesktopIdentityFingerprint identityFingerprint,
            Instant issuedAt,
            Instant expiresAt
    );

    Optional<DesktopAuthExchange> consume(
            DesktopAuthTokenHash desktopStateHash,
            DesktopAuthTokenHash handoffCodeHash,
            PkceS256Challenge pkceChallenge,
            Instant consumedAt
    );
}
