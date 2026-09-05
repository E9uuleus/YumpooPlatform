package com.yumpoo.platform.workitem.application;

import java.util.List;
import java.util.UUID;

public record ContentInitializationCommand(
        UUID companyId,
        UUID projectId,
        String templateKey,
        int templateVersion,
        UUID actorUserId,
        List<Blueprint> blueprints
) {
    public ContentInitializationCommand {
        blueprints = List.copyOf(blueprints);
    }

    public record Blueprint(
            String contentCode,
            String displayName,
            String colorToken,
            int sortOrder
    ) {
    }
}
