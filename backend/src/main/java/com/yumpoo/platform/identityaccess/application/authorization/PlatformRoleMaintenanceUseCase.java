package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.UUID;

public interface PlatformRoleMaintenanceUseCase {
    PlatformRoleMutationResult execute(MaintenanceRoleCommand command);

    void requireInitialIdentityBootstrapOpen(UUID companyId);

    InitialRoleBootstrapResult bootstrapInitialRoles(InitialRoleBootstrapCommand command);
}
