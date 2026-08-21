package com.yumpoo.platform.catalog.application.workspace;

import java.util.UUID;

public record WorkspaceGovernanceState(UUID workspaceId, UUID companyId, String code,
        String status, long currentProjectCount, long rowVersion) {
}
