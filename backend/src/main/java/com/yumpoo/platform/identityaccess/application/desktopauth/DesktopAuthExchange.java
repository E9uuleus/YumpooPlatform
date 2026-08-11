package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.time.Instant;
import java.util.Objects;

public record DesktopAuthExchange(
        DesktopIdentityFingerprint identityFingerprint,
        Instant handoffIssuedAt,
        Instant consumedAt
) {

    public DesktopAuthExchange {
        Objects.requireNonNull(identityFingerprint, "identityFingerprint must not be null");
        Objects.requireNonNull(handoffIssuedAt, "handoffIssuedAt must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        if (consumedAt.isBefore(handoffIssuedAt)) {
            throw new IllegalArgumentException("consumedAt must not precede handoffIssuedAt");
        }
    }
}
