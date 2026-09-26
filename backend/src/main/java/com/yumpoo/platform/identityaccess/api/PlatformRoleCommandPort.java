package com.yumpoo.platform.identityaccess.api;

public interface PlatformRoleCommandPort {

    PlatformRoleTierChangeResult changeTier(PlatformRoleTierChangeCommand command);

    PlatformRoleCommandReceipt grant(PlatformRoleGrantCommand command);

    PlatformRoleCommandReceipt revoke(PlatformRoleRevokeCommand command);
}
