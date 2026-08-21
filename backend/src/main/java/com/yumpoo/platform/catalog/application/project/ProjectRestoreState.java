package com.yumpoo.platform.catalog.application.project;

public record ProjectRestoreState(
        ProjectApplicationSnapshot project,
        boolean ownerMembershipActive
) {}
