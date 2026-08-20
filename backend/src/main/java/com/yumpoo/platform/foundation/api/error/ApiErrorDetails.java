package com.yumpoo.platform.foundation.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiErrorDetails(
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason
) {
    public static final ApiErrorDetails EMPTY = new ApiErrorDetails(null);
}
