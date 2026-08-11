package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class DesktopAuthTokenHasher {

    public DesktopAuthTokenHash hash(DesktopAuthToken token) {
        Objects.requireNonNull(token, "token must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.value().getBytes(StandardCharsets.US_ASCII));
            return new DesktopAuthTokenHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
