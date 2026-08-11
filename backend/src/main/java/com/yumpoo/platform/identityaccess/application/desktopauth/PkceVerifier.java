package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PkceVerifier {

    private static final Pattern VERIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    private final String value;

    private PkceVerifier(String value) {
        this.value = value;
    }

    public static PkceVerifier of(String value) {
        Objects.requireNonNull(value, "PKCE verifier must not be null");
        if (!VERIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("PKCE verifier has an invalid format");
        }
        return new PkceVerifier(value);
    }

    public PkceS256Challenge challenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return new PkceS256Challenge(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "PkceVerifier[REDACTED]";
    }
}
