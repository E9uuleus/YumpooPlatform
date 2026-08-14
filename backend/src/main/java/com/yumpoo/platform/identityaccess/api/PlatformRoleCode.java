package com.yumpoo.platform.identityaccess.api;

public enum PlatformRoleCode {
    COMPANY_ADMIN(ScopeType.COMPANY),
    APP_MANAGER(ScopeType.PLATFORM);

    private final ScopeType scopeType;

    PlatformRoleCode(ScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public ScopeType scopeType() {
        return scopeType;
    }

    public enum ScopeType {
        COMPANY,
        PLATFORM
    }
}
