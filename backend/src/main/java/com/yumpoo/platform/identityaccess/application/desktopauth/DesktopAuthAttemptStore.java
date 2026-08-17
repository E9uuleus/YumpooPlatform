package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

    default void createProduct(ProductDesktopAuthAttempt attempt) {
        throw new UnsupportedOperationException("product desktop authentication is unavailable");
    }

    default boolean claimProductAuthorization(DesktopAuthTokenHash stateHash, Instant claimedAt) {
        throw new UnsupportedOperationException("product desktop authentication is unavailable");
    }

    default boolean issueProductHandoff(
            DesktopAuthTokenHash stateHash,
            DesktopAuthTokenHash handoffCodeHash,
            UUID userId,
            Instant issuedAt,
            Instant expiresAt
    ) {
        throw new UnsupportedOperationException("product desktop authentication is unavailable");
    }

    default Optional<ProductDesktopAuthExchange> consumeProduct(
            DesktopAuthTokenHash stateHash,
            DesktopAuthTokenHash handoffCodeHash,
            PkceS256Challenge pkceChallenge,
            Instant consumedAt
    ) {
        throw new UnsupportedOperationException("product desktop authentication is unavailable");
    }
}
