package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;

import java.util.Objects;
import java.util.UUID;

public record DirectoryMemberProvisioningResult(
        UUID userId,
        UUID externalIdentityId,
        EmploymentStatus employmentStatus,
        AccountStatus accountStatus,
        long rowVersion,
        boolean created,
        boolean profileChanged
) {

    public DirectoryMemberProvisioningResult {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(externalIdentityId, "externalIdentityId must not be null");
        Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
    }
}
