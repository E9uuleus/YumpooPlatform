package com.yumpoo.platform.identityaccess.application.authorization;

public enum MaintenanceRoleMode {
    BOOTSTRAP("APP_MANAGER_BOOTSTRAP"),
    BREAK_GLASS("APP_MANAGER_BREAK_GLASS");

    private final String systemCode;

    MaintenanceRoleMode(String systemCode) {
        this.systemCode = systemCode;
    }

    public String systemCode() {
        return systemCode;
    }
}
