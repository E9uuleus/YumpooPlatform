package com.yumpoo.platform.identityaccess.application.authorization;

public interface RoleAssignmentQueryRepository {
    RoleAssignmentPage find(RoleAssignmentQuery query);
}
