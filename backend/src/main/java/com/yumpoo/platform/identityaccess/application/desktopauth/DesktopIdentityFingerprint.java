package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.util.Objects;
import java.util.regex.Pattern;

public record DesktopIdentityFingerprint(
        String corpFingerprint,
        String memberFingerprint
) {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public DesktopIdentityFingerprint {
        requireFingerprint(corpFingerprint, "corpFingerprint");
        requireFingerprint(memberFingerprint, "memberFingerprint");
    }

    private static void requireFingerprint(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
    }
}
