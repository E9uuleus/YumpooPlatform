package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.UUID;

public interface PlatformRoleMaintenanceUseCase {
    MaintenanceRoleActor ensureAvailableAppManager(
            UUID companyId,
            UUID preferredTargetUserId,
            String reasonReference
    );

    PlatformRoleMutationResult execute(MaintenanceRoleCommand command);

    void requireInitialIdentityBootstrapOpen(UUID companyId);

    InitialRoleBootstrapResult bootstrapInitialRoles(InitialRoleBootstrapCommand command);
}
