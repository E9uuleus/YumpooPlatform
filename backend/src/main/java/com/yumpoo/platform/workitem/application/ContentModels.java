package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContentModels {
    private ContentModels() {}

    public record ContentLocator(UUID contentId, UUID projectId) {}
    public record BlueprintOption(String blueprintCode, String displayName, String workItemType,
                                  String defaultViewType) {}
    public record WorkflowStatusOption(String statusCode, String displayName, String statusCategory,
                                       int sortOrder, boolean initial, boolean terminal) {}
    public record ProjectContentCatalog(List<ContentView> items,
            List<BlueprintOption> blueprintOptions, List<WorkflowStatusOption> workflowStatusOptions,
            boolean canCreate) {}
    public record ContentView(UUID id, UUID projectId, String code, String name, String description,
            String workItemType, String status, String defaultViewType,
            ContentViewConfig viewConfig, String appliedTemplateKey, int appliedTemplateVersion,
            String appliedBlueprintCode, long rowVersion, String etag, Instant createdAt,
            UUID createdByUserId, Instant updatedAt, UUID updatedByUserId, Instant archivedAt,
            UUID archivedByUserId) {}
}
