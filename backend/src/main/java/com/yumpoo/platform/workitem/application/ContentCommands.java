package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public final class ContentCommands {
    private ContentCommands() {}
    public record Create(CurrentActor actor, UUID projectId, String name, String colorToken,
                         UUID idempotencyKey, RequestHash requestHash) {}
    public record Update(CurrentActor actor, UUID projectId, UUID contentId,
                         long expectedCatalogVersion, String name, String colorToken,
                         Boolean active, Integer sortOrder) {}
    public record Delete(CurrentActor actor, UUID projectId, UUID contentId,
                         long expectedCatalogVersion) {}
}
