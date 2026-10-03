package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.List;
import java.util.UUID;

public final class ConnectColumnCommands {
    private ConnectColumnCommands() {}
    public record Create(CurrentActor actor, UUID projectId, String name, List<UUID> targetProjectIds,
            UUID idempotencyKey, RequestHash requestHash) {}
    public record Update(CurrentActor actor, UUID projectId, UUID columnId, long expectedVersion,
            String name, List<UUID> targetProjectIds) {}
    public record Delete(CurrentActor actor, UUID projectId, UUID columnId, long expectedVersion,
            UUID idempotencyKey, RequestHash requestHash) {}
}
