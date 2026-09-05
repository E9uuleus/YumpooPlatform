package com.yumpoo.platform.templateworkflow.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectTemplateView(
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
        List<ContentBlueprintView> contentBlueprints,
        List<WorkflowStatusView> statuses,
        List<WorkflowTransitionView> transitions
) {
    public ProjectTemplateView {
        contentBlueprints = List.copyOf(contentBlueprints);
        statuses = List.copyOf(statuses);
        transitions = List.copyOf(transitions);
    }

    public record ContentBlueprintView(
            String contentCode,
            String displayName,
            String colorToken,
            int sortOrder
    ) {
    }

    public record WorkflowStatusView(
            String statusCode,
            String displayName,
            String statusCategory,
            int sortOrder,
            boolean initial,
            boolean terminal
    ) {
    }

    public record WorkflowTransitionView(
            String fromStatus,
            String toStatus,
            String requiredPermission,
            boolean requiresResolution
    ) {
    }
}
