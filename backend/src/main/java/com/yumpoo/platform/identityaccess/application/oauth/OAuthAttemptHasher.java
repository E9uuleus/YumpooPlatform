package com.yumpoo.platform.identityaccess.application.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class OAuthAttemptHasher {

    public OAuthAttemptHash hash(OAuthAttemptToken token) {
        Objects.requireNonNull(token, "token must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.value().getBytes(StandardCharsets.US_ASCII));
            return new OAuthAttemptHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
