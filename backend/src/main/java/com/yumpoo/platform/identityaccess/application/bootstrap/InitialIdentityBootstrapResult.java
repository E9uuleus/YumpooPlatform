package com.yumpoo.platform.identityaccess.application.bootstrap;

import java.util.Objects;
import java.util.UUID;

public record InitialIdentityBootstrapResult(
        UUID directoryRunId,
        UUID appManagerAssignmentId,
        UUID companyAdminAssignmentId
) {
    public InitialIdentityBootstrapResult {
        Objects.requireNonNull(directoryRunId, "directoryRunId must not be null");
        Objects.requireNonNull(appManagerAssignmentId, "appManagerAssignmentId must not be null");
        Objects.requireNonNull(companyAdminAssignmentId, "companyAdminAssignmentId must not be null");
    }
}
