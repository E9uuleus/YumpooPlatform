package com.yumpoo.platform.identityaccess.application.authorization;

public interface PlatformRoleMaintenanceUseCase {
    PlatformRoleMutationResult execute(MaintenanceRoleCommand command);
}
