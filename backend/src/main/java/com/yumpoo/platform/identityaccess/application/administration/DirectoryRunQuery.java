package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

public record DirectoryRunQuery(
        String status,
        String triggerType,
        OffsetPageRequest pageRequest
) {
    public DirectoryRunQuery {
        status = normalize(status);
        triggerType = normalize(triggerType);
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
