package com.yumpoo.platform.catalog.application.project;

public record ProjectCapabilities(
        boolean canUpdateSettings,
        boolean canActivate,
        boolean canManageMembers,
        boolean canReassignOwner,
        boolean canManageProductLinks,
        boolean canArchive,
        boolean canRestore,
        boolean canMoveWorkspace,
        boolean canOverrideArchive
) {
    public ProjectCapabilities(boolean canUpdateSettings, boolean canActivate,
                               boolean canManageMembers, boolean canReassignOwner) {
        this(canUpdateSettings, canActivate, canManageMembers, canReassignOwner,
                false, false, false, false, false);
    }
}
