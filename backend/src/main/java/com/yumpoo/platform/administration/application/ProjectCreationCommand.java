package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import com.yumpoo.platform.workitem.api.InitialStructureColors;

import java.util.UUID;

public record ProjectCreationCommand(
        CurrentActor actor,
        String name,
        String description,
        UUID idempotencyKey,
        RequestHash requestHash,
        String clientType,
        String clientVersion,
        InitialStructureColors initialColors
) {
    public ProjectCreationCommand(CurrentActor actor, String name, String description, UUID idempotencyKey,
            RequestHash requestHash, String clientType, String clientVersion) {
        this(actor, name, description, idempotencyKey, requestHash, clientType, clientVersion, InitialStructureColors.DEFAULT);
    }
}
