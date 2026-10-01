package com.yumpoo.platform.foundation.api.error;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public record ApiErrorDetails(
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ApiBlocker> blockers,
        @JsonIgnore Map<String, Object> safeDetails
) {
    public ApiErrorDetails {
        blockers = List.copyOf(blockers);
        safeDetails = Map.copyOf(safeDetails);
    }

    public ApiErrorDetails(String reason, List<ApiBlocker> blockers) {
        this(reason, blockers, Map.of());
    }
    public static final ApiErrorDetails EMPTY = new ApiErrorDetails(null, List.of());

    @JsonAnyGetter
    public Map<String, Object> additionalDetails() { return safeDetails; }

    public record ApiBlocker(String code, long count) {
    }
}
