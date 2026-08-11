package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.util.Objects;
import java.util.regex.Pattern;

public record DesktopAuthTokenHash(String value) {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public DesktopAuthTokenHash {
        Objects.requireNonNull(value, "desktop auth token hash must not be null");
        if (!SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("desktop auth token hash must be lowercase SHA-256 hex");
        }
    }
}
