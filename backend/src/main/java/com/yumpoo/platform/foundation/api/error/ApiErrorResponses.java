package com.yumpoo.platform.foundation.api.error;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;

import java.util.Comparator;
import java.util.List;

final class ApiErrorResponses {

    private ApiErrorResponses() {
    }

    static ApiErrorResponse from(ApplicationException exception, String requestId) {
        return create(
                exception.errorCode(),
                exception.getMessage(),
                requestId,
                exception.fieldViolations().stream().map(ApiErrorResponses::from).toList()
        );
    }

    static ApiErrorResponse create(
            StandardErrorCode code,
            String message,
            String requestId,
            List<ApiFieldError> fieldErrors
    ) {
        List<ApiFieldError> stableFieldErrors = fieldErrors.stream()
                .sorted(Comparator.comparing(ApiFieldError::field).thenComparing(ApiFieldError::code))
                .toList();
        return new ApiErrorResponse(
                code.name(),
                message,
                requestId,
                code.retryable(),
                stableFieldErrors,
                EmptyErrorDetails.INSTANCE
        );
    }

    private static ApiFieldError from(FieldViolation violation) {
        return new ApiFieldError(violation.field(), violation.code(), violation.message());
    }
}
