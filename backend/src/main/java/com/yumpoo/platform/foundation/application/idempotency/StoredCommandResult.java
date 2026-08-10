package com.yumpoo.platform.foundation.application.idempotency;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 允许安全持久化和重放的成功响应。
 */
public record StoredCommandResult(
        int httpStatus,
        String responseJson,
        UUID resourceId,
        String etag
) {

    private static final int MAX_ETAG_LENGTH = 128;
    private static final Pattern STRONG_DECIMAL_ETAG = Pattern.compile("^\"[0-9]+\"$");

    public StoredCommandResult {
        if (httpStatus < 200 || httpStatus > 299) {
            throw new IllegalArgumentException("httpStatus must be a successful 2xx status");
        }
        Objects.requireNonNull(responseJson, "responseJson must not be null");
        if (responseJson.isBlank()) {
            throw new IllegalArgumentException("responseJson must not be blank");
        }
        if (etag != null && !isSupportedStrongEtag(etag)) {
            throw new IllegalArgumentException(
                    "etag must be null or a strong decimal ETag containing a non-negative long"
            );
        }
    }

    private static boolean isSupportedStrongEtag(String etag) {
        if (etag.length() > MAX_ETAG_LENGTH || !STRONG_DECIMAL_ETAG.matcher(etag).matches()) {
            return false;
        }
        try {
            Long.parseLong(etag, 1, etag.length() - 1, 10);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
