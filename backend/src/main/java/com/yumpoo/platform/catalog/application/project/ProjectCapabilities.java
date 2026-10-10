package com.yumpoo.platform.catalog.application.project;

public record ProjectCapabilities(
        boolean canUpdateSettings,
        boolean canManageMembers,
        boolean canReassignOwner,
        boolean canArchive,
        boolean canRestore,
        boolean canMoveWorkspace,
        boolean canOverrideArchive,
        boolean canScheduleDeletion,
        boolean canCancelDeletion
) {
    public ProjectCapabilities(boolean update,boolean members,boolean owner,boolean archive,boolean restore,
                               boolean move,boolean override) {
        this(update,members,owner,archive,restore,move,override,false,false);
    }
    public ProjectCapabilities(boolean canUpdateSettings,
                               boolean canManageMembers, boolean canReassignOwner) {
        this(canUpdateSettings, canManageMembers, canReassignOwner,
                false, false, false, false, false, false);
    }
}
