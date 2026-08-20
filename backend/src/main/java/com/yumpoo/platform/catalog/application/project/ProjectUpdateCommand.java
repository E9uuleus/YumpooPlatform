package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProjectUpdateCommand(
        CurrentActor actor,
        UUID projectId,
        long expectedRowVersion,
        String name,
        String description,
        String customerName,
        String customerReference,
        String deliverySite,
        String contactNote
) {
}
