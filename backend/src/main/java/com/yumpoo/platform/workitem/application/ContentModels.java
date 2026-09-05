package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContentModels {
    private ContentModels() {}

    public record ContentLocator(UUID contentId, UUID projectId) {}
    public record ProjectContentCatalog(List<ContentView> items, long rowVersion, String etag,
                                        boolean canManage) {
        public ProjectContentCatalog { items = List.copyOf(items); }
    }
    public record ContentView(UUID id, UUID projectId, String code, String name,
            String colorToken, int sortOrder, boolean active, boolean protectedContent,
            boolean inUse, long rowVersion, Instant createdAt, UUID createdByUserId,
            Instant updatedAt, UUID updatedByUserId) {}
}
