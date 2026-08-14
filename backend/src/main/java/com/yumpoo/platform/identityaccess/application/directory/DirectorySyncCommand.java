package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventActorType;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;

import java.util.Objects;

public record DirectorySyncCommand(
        String triggerKey,
        DirectorySyncTriggerType triggerType,
        EventActor actor,
        String requestId
) {

    public DirectorySyncCommand {
        Objects.requireNonNull(triggerKey, "triggerKey must not be null");
        triggerKey = triggerKey.trim();
        if (triggerKey.isEmpty() || triggerKey.length() > 256) {
            throw new IllegalArgumentException("triggerKey must be between 1 and 256 characters");
        }
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requestId = RequestIdContext.requireValid(requestId, "requestId");
        if (triggerType == DirectorySyncTriggerType.MANUAL
                && actor.type() != EventActorType.USER) {
            throw new IllegalArgumentException("MANUAL sync requires a USER actor");
        }
        if (triggerType == DirectorySyncTriggerType.SCHEDULED
                && actor.type() != EventActorType.SYSTEM) {
            throw new IllegalArgumentException("SCHEDULED sync requires a SYSTEM actor");
        }
    }

    @Override
    public String toString() {
        return "DirectorySyncCommand[triggerType=" + triggerType + ", requestId=" + requestId + "]";
    }
}
