package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Objects;
import java.util.UUID;

public record InitialRoleBootstrapResult(
        UUID appManagerAssignmentId,
        UUID companyAdminAssignmentId
) {
    public InitialRoleBootstrapResult {
        Objects.requireNonNull(appManagerAssignmentId, "appManagerAssignmentId must not be null");
        Objects.requireNonNull(companyAdminAssignmentId, "companyAdminAssignmentId must not be null");
    }
}
