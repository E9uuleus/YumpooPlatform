package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProjectCreationCommand(
        CurrentActor actor,
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
        UUID idempotencyKey,
        RequestHash requestHash,
        String clientType,
        String clientVersion
) {
}
