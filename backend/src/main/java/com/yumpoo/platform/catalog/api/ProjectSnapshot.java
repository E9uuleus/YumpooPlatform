package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectSnapshot(
        UUID projectId,
        UUID companyId,
        UUID workspaceId,
        String code,
        String name,
        String description,
        String lifecycle,
        UUID ownerUserId,
        long rowVersion
) {
}
