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
        long authorizationVersion,
        long rowVersion,
        DirectoryMemberProvisioningOutcome outcome
) {

    public DirectoryMemberProvisioningResult {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(externalIdentityId, "externalIdentityId must not be null");
        Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (authorizationVersion < 0 || rowVersion < 0) {
            throw new IllegalArgumentException("versions must not be negative");
        }
    }

    public boolean created() {
        return outcome == DirectoryMemberProvisioningOutcome.CREATED;
    }

    public boolean profileChanged() {
        return outcome == DirectoryMemberProvisioningOutcome.CREATED
                || outcome == DirectoryMemberProvisioningOutcome.UPDATED;
    }
}
