package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record WorkspaceSnapshot(
        UUID workspaceId,
        UUID companyId,
        long rowVersion
) {
}
