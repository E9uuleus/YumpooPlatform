package com.yumpoo.platform.identityaccess.application.authorization;

public enum MemberRoleTier {
    COMPANY_MEMBER, COMPANY_ADMIN, APP_MANAGER;

    public static MemberRoleTier of(RoleUserSnapshot user) {
        return user.activeRoles().contains(ManagedPlatformRole.APP_MANAGER) ? APP_MANAGER
                : user.activeRoles().contains(ManagedPlatformRole.COMPANY_ADMIN) ? COMPANY_ADMIN : COMPANY_MEMBER;
    }
}
