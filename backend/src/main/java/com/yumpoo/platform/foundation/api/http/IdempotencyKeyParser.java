package com.yumpoo.platform.foundation.api.http;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class IdempotencyKeyParser {

    public static final String HEADER_NAME = "Idempotency-Key";
    private static final int MAX_LENGTH = 64;

    public UUID parseRequired(String headerValue) {
        if (headerValue == null || headerValue.isBlank() || headerValue.length() > MAX_LENGTH) {
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        }
        try {
            UUID parsed = UUID.fromString(headerValue);
            if (!parsed.toString().equalsIgnoreCase(headerValue)) {
                throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
            }
            return parsed;
        } catch (IllegalArgumentException ignored) {
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        }
    }
}
