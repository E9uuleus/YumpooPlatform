package com.yumpoo.platform.catalog.application.project;

import java.util.UUID;

public record ProjectApplicationSnapshot(
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
