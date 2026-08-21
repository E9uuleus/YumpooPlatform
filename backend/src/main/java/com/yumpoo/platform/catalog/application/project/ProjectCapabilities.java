package com.yumpoo.platform.catalog.application.project;

public record ProjectCapabilities(
        boolean canUpdateSettings,
        boolean canActivate,
        boolean canManageMembers,
        boolean canReassignOwner,
        boolean canManageProductLinks
) {
    public ProjectCapabilities(boolean canUpdateSettings, boolean canActivate,
                               boolean canManageMembers, boolean canReassignOwner) {
        this(canUpdateSettings, canActivate, canManageMembers, canReassignOwner, false);
    }
}
