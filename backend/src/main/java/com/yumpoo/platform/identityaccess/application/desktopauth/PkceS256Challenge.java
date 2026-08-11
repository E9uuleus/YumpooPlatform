package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.util.Objects;
import java.util.regex.Pattern;

public record PkceS256Challenge(String value) {

    private static final Pattern CHALLENGE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public PkceS256Challenge {
        Objects.requireNonNull(value, "PKCE S256 challenge must not be null");
        if (!CHALLENGE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("PKCE S256 challenge has an invalid format");
        }
    }
}
