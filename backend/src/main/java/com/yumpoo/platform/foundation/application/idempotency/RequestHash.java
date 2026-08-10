package com.yumpoo.platform.foundation.application.idempotency;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 规范化请求内容的 SHA-256 十六进制摘要。
 */
public record RequestHash(String value) {

    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public RequestHash {
        Objects.requireNonNull(value, "value must not be null");
        if (!LOWERCASE_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a 64-character lowercase SHA-256 hash");
        }
    }
}
