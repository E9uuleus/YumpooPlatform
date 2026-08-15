package com.yumpoo.platform.identityaccess.api;

public interface PlatformRoleCommandPort {

    PlatformRoleCommandReceipt grant(PlatformRoleGrantCommand command);

    PlatformRoleCommandReceipt revoke(PlatformRoleRevokeCommand command);
}
