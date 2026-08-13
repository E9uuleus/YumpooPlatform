package com.yumpoo.platform.identityaccess.domain.identity;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProfileHash(String value) {

    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public ProfileHash {
        Objects.requireNonNull(value, "profile hash must not be null");
        if (!LOWERCASE_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("profile hash must be lowercase SHA-256 hex");
        }
    }

    @Override
    public String toString() {
        return "ProfileHash[REDACTED]";
    }
}
