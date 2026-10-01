package com.yumpoo.platform.catalog.application.project;

public record ProjectCapabilities(
        boolean canUpdateSettings,
        boolean canManageMembers,
        boolean canReassignOwner,
        boolean canArchive,
        boolean canRestore,
        boolean canMoveWorkspace,
        boolean canOverrideArchive
) {
    public ProjectCapabilities(boolean canUpdateSettings,
                               boolean canManageMembers, boolean canReassignOwner) {
        this(canUpdateSettings, canManageMembers, canReassignOwner,
                false, false, false, false);
    }
}
