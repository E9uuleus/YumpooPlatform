package com.yumpoo.platform.identityaccess.application.authorization;

public record AvailabilitySnapshot(
        GovernanceStateSnapshot state,
        int availableCount
) {
}
