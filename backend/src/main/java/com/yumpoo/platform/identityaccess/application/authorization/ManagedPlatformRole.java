package com.yumpoo.platform.identityaccess.application.authorization;

public enum ManagedPlatformRole {
    APP_MANAGER("PLATFORM"),
    COMPANY_ADMIN("COMPANY");

    private final String scopeType;

    ManagedPlatformRole(String scopeType) {
        this.scopeType = scopeType;
    }

    public String scopeType() {
        return scopeType;
    }
}
