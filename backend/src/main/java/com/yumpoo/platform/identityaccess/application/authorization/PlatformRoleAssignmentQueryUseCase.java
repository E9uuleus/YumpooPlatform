package com.yumpoo.platform.identityaccess.application.authorization;

public interface PlatformRoleAssignmentQueryUseCase {
    RoleAssignmentPage find(RoleAssignmentQuery query);
}
