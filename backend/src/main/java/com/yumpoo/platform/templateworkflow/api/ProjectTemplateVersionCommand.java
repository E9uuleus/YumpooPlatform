package com.yumpoo.platform.templateworkflow.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectTemplateVersionCommand(
        String templateKey,
        int version,
        long expectedRowVersion,
        UUID actorUserId,
        String reason,
        Instant changedAt
) {
    public ProjectTemplateVersionCommand {
        Objects.requireNonNull(templateKey, "templateKey must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
    }
}
