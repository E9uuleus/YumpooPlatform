package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;

import java.util.Set;

public record IdentityMemberQuery(
        String name,
        String externalUserId,
        String employmentStatus,
        String accountStatus,
        String platformRole,
        OffsetPageRequest pageRequest
) {
    private static final Set<String> EMPLOYMENT_STATUSES = Set.of("ACTIVE", "LEFT");
    private static final Set<String> ACCOUNT_STATUSES = Set.of("ENABLED", "DISABLED");

    public IdentityMemberQuery(String name, String externalUserId, String employmentStatus,
            String accountStatus, OffsetPageRequest pageRequest) {
        this(name, externalUserId, employmentStatus, accountStatus, null, pageRequest);
    }

    public IdentityMemberQuery {
        name = normalize(name);
        externalUserId = normalize(externalUserId);
        employmentStatus = normalize(employmentStatus);
        accountStatus = normalize(accountStatus);
        platformRole = normalize(platformRole);
        requireAllowed(platformRole, Set.of("COMPANY_MEMBER", "COMPANY_ADMIN", "APP_MANAGER"));
        requireAllowed(employmentStatus, EMPLOYMENT_STATUSES);
        requireAllowed(accountStatus, ACCOUNT_STATUSES);
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void requireAllowed(String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        }
    }
}
