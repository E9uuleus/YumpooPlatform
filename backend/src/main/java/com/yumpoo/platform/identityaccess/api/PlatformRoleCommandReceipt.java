package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;

public record PlatformRoleCommandReceipt(
        PlatformRoleAssignmentMutation mutation,
        boolean replayed
) {

    public PlatformRoleCommandReceipt {
        Objects.requireNonNull(mutation, "mutation must not be null");
    }
}
