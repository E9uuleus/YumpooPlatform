package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.UUID;

public record GovernanceStateSnapshot(
        UUID companyId,
        GovernanceLifecycleStatus lifecycleStatus,
        long eventVersion,
        long rowVersion
) {
}
