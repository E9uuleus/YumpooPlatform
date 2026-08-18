package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Objects;
import java.util.UUID;

public record InitialRoleBootstrapCommand(
        UUID companyId,
        UUID appManagerUserId,
        UUID companyAdminUserId,
        UUID directoryRunId,
        String reasonReference
) {
    public InitialRoleBootstrapCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(appManagerUserId, "appManagerUserId must not be null");
        Objects.requireNonNull(companyAdminUserId, "companyAdminUserId must not be null");
        Objects.requireNonNull(directoryRunId, "directoryRunId must not be null");
        if (appManagerUserId.equals(companyAdminUserId)) {
            throw new IllegalArgumentException("initial role holders must be distinct");
        }
        reasonReference = GrantPlatformRoleCommand.validateReason(reasonReference);
    }
}
