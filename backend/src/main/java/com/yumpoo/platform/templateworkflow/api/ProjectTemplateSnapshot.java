package com.yumpoo.platform.templateworkflow.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProjectTemplateSnapshot(
        UUID templateVersionId,
        String templateKey,
        int version,
        String versionCode,
        String projectType,
        String displayName,
        String lifecycleStatus,
        long rowVersion,
        Instant publishedAt,
        Instant retiredAt,
        List<ContentBlueprint> contentBlueprints,
        List<WorkflowStatus> statuses,
        List<WorkflowTransition> transitions
) {
    public ProjectTemplateSnapshot {
        Objects.requireNonNull(templateVersionId, "templateVersionId must not be null");
        contentBlueprints = List.copyOf(contentBlueprints);
        statuses = List.copyOf(statuses);
        transitions = List.copyOf(transitions);
    }

    public record ContentBlueprint(
            String contentCode,
            String displayName,
            String workItemType,
            String defaultViewType,
            int sortOrder
    ) {
    }

    public record WorkflowStatus(
            String statusCode,
            String displayName,
            String statusCategory,
            int sortOrder,
            boolean initial,
            boolean terminal
    ) {
    }

    public record WorkflowTransition(
            String fromStatus,
            String toStatus,
            String requiredPermission,
            boolean requiresResolution
    ) {
    }
}
