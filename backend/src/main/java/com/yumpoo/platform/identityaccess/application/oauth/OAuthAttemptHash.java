package com.yumpoo.platform.identityaccess.application.oauth;

import java.util.Objects;
import java.util.regex.Pattern;

/** SHA-256 后的一次性 OAuth 证明；数据库只能保存该形态。 */
public record OAuthAttemptHash(String value) {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public OAuthAttemptHash {
        Objects.requireNonNull(value, "OAuth attempt hash must not be null");
        if (!SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("OAuth attempt hash must be lowercase SHA-256 hex");
        }
    }
}
