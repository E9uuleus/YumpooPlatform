package com.yumpoo.platform.catalog.application.project;

public record ProjectCapabilities(
        boolean canUpdateSettings,
        boolean canActivate,
        boolean canManageMembers,
        boolean canReassignOwner
) {
}
