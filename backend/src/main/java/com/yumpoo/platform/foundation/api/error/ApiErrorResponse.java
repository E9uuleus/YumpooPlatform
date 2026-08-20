package com.yumpoo.platform.foundation.api.error;

import java.util.List;
import java.util.Objects;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        boolean retryable,
        List<ApiFieldError> fieldErrors,
        ApiErrorDetails details
) {

    public ApiErrorResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        fieldErrors = List.copyOf(fieldErrors);
        Objects.requireNonNull(details, "details must not be null");
    }
}
