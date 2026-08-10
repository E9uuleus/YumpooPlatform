package com.yumpoo.platform.identityaccess.application.oauth;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record OAuthAttempt(
        OAuthAttemptHash stateHash,
        OAuthAttemptHash nonceHash,
        String requestId,
        Instant createdAt,
        Instant expiresAt
) {

    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    public OAuthAttempt {
        Objects.requireNonNull(stateHash, "stateHash must not be null");
        Objects.requireNonNull(nonceHash, "nonceHash must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (stateHash.equals(nonceHash)) {
            throw new IllegalArgumentException("state and nonce must be distinct");
        }
        if (!REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            throw new IllegalArgumentException("requestId has an invalid format");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
