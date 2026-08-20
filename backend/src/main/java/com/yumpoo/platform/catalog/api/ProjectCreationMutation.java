package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectCreationMutation(
        UUID companyId,
        UUID workspaceId,
        String code,
        String name,
        String description,
        String projectType,
        UUID ownerUserId,
        String templateKey,
        int templateVersion,
        String customerName,
        String customerReference,
        String deliverySite,
        String contactNote,
        UUID actorUserId
) {
}
