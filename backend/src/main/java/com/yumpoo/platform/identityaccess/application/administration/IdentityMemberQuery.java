package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

public record IdentityMemberQuery(
        String name,
        String externalUserId,
        String employmentStatus,
        String accountStatus,
        OffsetPageRequest pageRequest
) {
    public IdentityMemberQuery {
        name = normalize(name);
        externalUserId = normalize(externalUserId);
        employmentStatus = normalize(employmentStatus);
        accountStatus = normalize(accountStatus);
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
