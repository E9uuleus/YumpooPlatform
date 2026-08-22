package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public final class ContentCommands {
    private ContentCommands() {}
    public record Create(CurrentActor actor, UUID projectId, String code, String name,
                         String description, String blueprintCode, UUID idempotencyKey,
                         RequestHash requestHash) {}
    public record Update(CurrentActor actor, UUID contentId, long expectedVersion, String name,
                         String description, String defaultViewType, JsonNode viewConfig) {}
    public record Transition(CurrentActor actor, UUID contentId, long expectedVersion,
                             UUID idempotencyKey, RequestHash requestHash) {}
}
