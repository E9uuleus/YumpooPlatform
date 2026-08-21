package com.yumpoo.platform.catalog.api;

public record ProjectRestoreSnapshot(
        ProjectSnapshot project,
        boolean ownerMembershipActive
) {}
