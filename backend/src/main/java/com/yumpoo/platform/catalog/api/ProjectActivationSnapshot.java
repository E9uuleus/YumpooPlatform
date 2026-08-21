package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectActivationSnapshot(
        ProjectSnapshot project,
        boolean ownerMembershipActive
) {
    public UUID ownerUserId() { return project.ownerUserId(); }
}
