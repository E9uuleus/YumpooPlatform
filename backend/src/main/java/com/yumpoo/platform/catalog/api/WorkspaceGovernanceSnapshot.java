package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record WorkspaceGovernanceSnapshot(
        UUID workspaceId,
        UUID companyId,
        String code,
        String status,
        long currentProjectCount,
        long rowVersion
) {
}
