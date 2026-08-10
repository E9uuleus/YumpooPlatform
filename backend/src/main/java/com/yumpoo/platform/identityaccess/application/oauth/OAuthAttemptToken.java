package com.yumpoo.platform.identityaccess.application.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一次性 OAuth state/nonce。值仅允许在协议边界显式读取，字符串化始终脱敏。
 */
public final class OAuthAttemptToken {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final String REDACTED = "OAuthAttemptToken[REDACTED]";

    private final String value;

    private OAuthAttemptToken(String value) {
        this.value = value;
    }

    public static OAuthAttemptToken of(String value) {
        Objects.requireNonNull(value, "OAuth attempt token must not be null");
        if (!TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("OAuth attempt token must be a 256-bit base64url value");
        }
        return new OAuthAttemptToken(value);
    }

    /** 仅供 HTTP/企微协议边界使用；不得写入数据库或日志。 */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OAuthAttemptToken that)) {
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
        return REDACTED;
    }
}
