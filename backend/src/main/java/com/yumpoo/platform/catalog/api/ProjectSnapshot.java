package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectSnapshot(
        UUID projectId,
        UUID companyId,
        UUID workspaceId,
        String code,
        String name,
        String description,
        String projectType,
        String lifecycle,
        UUID ownerUserId,
        String templateKey,
        int templateVersion,
        String customerName,
        String customerReference,
        String deliverySite,
        String contactNote,
        long rowVersion
) {
}
