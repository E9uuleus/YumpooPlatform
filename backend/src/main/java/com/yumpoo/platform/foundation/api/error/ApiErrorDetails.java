package com.yumpoo.platform.foundation.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ApiErrorDetails(
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ApiBlocker> blockers,
        @JsonInclude(JsonInclude.Include.NON_NULL) java.util.UUID targetProjectId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long activeConnectionCount
) {
    public ApiErrorDetails(String reason, List<ApiBlocker> blockers) {
        this(reason, blockers, null, null);
    }
    public static final ApiErrorDetails EMPTY = new ApiErrorDetails(null, List.of());

    public record ApiBlocker(String code, long count) {
    }
}
