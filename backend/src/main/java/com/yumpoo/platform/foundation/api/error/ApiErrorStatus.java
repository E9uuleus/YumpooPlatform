package com.yumpoo.platform.foundation.api.error;

import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.http.HttpStatus;

final class ApiErrorStatus {

    private ApiErrorStatus() {
    }

    static HttpStatus forCode(StandardErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case ACCOUNT_DISABLED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS,
                    INVALID_STATE_TRANSITION, WORKLOG_LOCKED -> HttpStatus.CONFLICT;
            case VERSION_CONFLICT -> HttpStatus.PRECONDITION_FAILED;
            case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case FILE_TYPE_NOT_ALLOWED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CLIENT_UPGRADE_REQUIRED -> HttpStatus.UPGRADE_REQUIRED;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
