package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/** A 256-bit base64url desktop state or one-time handoff code. */
public final class DesktopAuthToken {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final String value;

    private DesktopAuthToken(String value) {
        this.value = value;
    }

    public static DesktopAuthToken of(String value) {
        Objects.requireNonNull(value, "desktop auth token must not be null");
        if (!TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("desktop auth token must be a 256-bit base64url value");
        }
        return new DesktopAuthToken(value);
    }

    /** Only protocol boundary code may read the raw value. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DesktopAuthToken that)) {
            return false;
        }
        return MessageDigest.isEqual(
                value.getBytes(StandardCharsets.US_ASCII),
                that.value.getBytes(StandardCharsets.US_ASCII)
        );
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "DesktopAuthToken[REDACTED]";
    }
}
