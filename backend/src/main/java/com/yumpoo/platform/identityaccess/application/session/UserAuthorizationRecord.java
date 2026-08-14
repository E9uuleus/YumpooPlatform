package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;

import java.util.Objects;
import java.util.UUID;

public record UserAuthorizationRecord(
        UUID userId,
        UUID companyId,
        EmploymentStatus employmentStatus,
        AccountStatus accountStatus,
        long authorizationVersion,
        long rowVersion
) {

    public UserAuthorizationRecord {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
        if (authorizationVersion < 0 || rowVersion < 0) {
            throw new IllegalArgumentException("versions must not be negative");
        }
    }

    public boolean activeAndEnabled() {
        return employmentStatus == EmploymentStatus.ACTIVE
                && accountStatus == AccountStatus.ENABLED;
    }

    public boolean employmentActive() {
        return employmentStatus == EmploymentStatus.ACTIVE;
    }

    public boolean accountEnabled() {
        return accountStatus == AccountStatus.ENABLED;
    }
}
