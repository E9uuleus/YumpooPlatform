package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;

import java.util.Objects;
import java.util.UUID;

public record AuthenticationUser(
        UUID userId,
        UUID companyId,
        String displayName,
        EmploymentStatus employmentStatus,
        AccountStatus accountStatus,
        EmploymentStatus providerEmploymentStatus,
        long authorizationVersion,
        long rowVersion
) {

    public AuthenticationUser {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
        Objects.requireNonNull(
                providerEmploymentStatus,
                "providerEmploymentStatus must not be null"
        );
        if (displayName.isBlank() || authorizationVersion < 0 || rowVersion < 0) {
            throw new IllegalArgumentException("authentication user facts are invalid");
        }
    }

    public boolean loginEligible() {
        return employmentStatus == EmploymentStatus.ACTIVE
                && accountStatus == AccountStatus.ENABLED
                && providerEmploymentStatus == EmploymentStatus.ACTIVE;
    }
}
