package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record WorkspaceUpdateCommand(
        CurrentActor actor,
        UUID workspaceId,
        long expectedRowVersion,
        String name,
        String description,
        int sortOrder
) {
}
