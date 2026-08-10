package com.yumpoo.platform.foundation.api.error;

import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

final class ApiErrorHeaders {

    private static final String REQUEST_IN_PROGRESS_RETRY_AFTER_SECONDS = "1";

    private ApiErrorHeaders() {
    }

    static void apply(HttpHeaders headers, ApiErrorResponse body) {
        headers.set(RequestIdContext.HEADER_NAME, body.requestId());
        if (requiresRetryAfter(body)) {
            headers.set(HttpHeaders.RETRY_AFTER, REQUEST_IN_PROGRESS_RETRY_AFTER_SECONDS);
        } else {
            headers.remove(HttpHeaders.RETRY_AFTER);
        }
    }

    static void apply(HttpServletResponse response, ApiErrorResponse body) {
        response.setHeader(RequestIdContext.HEADER_NAME, body.requestId());
        if (requiresRetryAfter(body)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, REQUEST_IN_PROGRESS_RETRY_AFTER_SECONDS);
        } else {
            response.setHeader(HttpHeaders.RETRY_AFTER, null);
        }
    }

    private static boolean requiresRetryAfter(ApiErrorResponse body) {
        return StandardErrorCode.REQUEST_IN_PROGRESS.name().equals(body.code());
    }
}
