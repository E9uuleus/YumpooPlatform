package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;
import java.time.Instant;

public record ProjectApplicationSnapshot(
        UUID projectId,
        UUID companyId,
        UUID workspaceId,
        String code,
        String name,
        String description,
        String lifecycle,
        UUID ownerUserId,
        long rowVersion,
        Instant endedAt
) {
}
