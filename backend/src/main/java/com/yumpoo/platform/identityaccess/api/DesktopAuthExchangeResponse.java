package com.yumpoo.platform.identityaccess.api;

import java.time.Instant;

public record DesktopAuthExchangeResponse(
        String sessionCredential,
        String csrfCredential,
        Instant absoluteExpiresAt
) {
    @Override
    public String toString() {
        return "DesktopAuthExchangeResponse[absoluteExpiresAt=" + absoluteExpiresAt
                + ", credentials=REDACTED]";
    }
}
