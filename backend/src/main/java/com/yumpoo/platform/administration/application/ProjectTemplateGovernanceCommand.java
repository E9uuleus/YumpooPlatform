package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.Objects;
import java.util.UUID;

public record ProjectTemplateGovernanceCommand(
        CurrentActor actor,
        String templateKey,
        int version,
        long expectedRowVersion,
        String reason,
        UUID idempotencyKey,
        RequestHash requestHash,
        String clientType,
        String clientVersion
) {
    public ProjectTemplateGovernanceCommand {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(templateKey, "templateKey must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
    }
}
