package com.yumpoo.platform.catalog.application.project;

public record ProjectActivationState(
        ProjectApplicationSnapshot project,
        boolean ownerMembershipActive
) {
}
