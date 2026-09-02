package com.yumpoo.platform.workitem.api;

import java.util.List;
import java.util.UUID;

public record ProjectContentInitialization(
        UUID companyId,
        UUID projectId,
        String templateKey,
        int templateVersion,
        UUID actorUserId,
        List<Blueprint> blueprints
) {
    public ProjectContentInitialization {
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
