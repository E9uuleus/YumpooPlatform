package com.yumpoo.platform.foundation.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ApiErrorDetails(
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ApiBlocker> blockers
) {
    public static final ApiErrorDetails EMPTY = new ApiErrorDetails(null, List.of());

    public record ApiBlocker(String code, long count) {
    }
}
