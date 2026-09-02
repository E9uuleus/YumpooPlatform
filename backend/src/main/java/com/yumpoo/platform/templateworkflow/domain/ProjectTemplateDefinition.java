package com.yumpoo.platform.templateworkflow.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProjectTemplateDefinition(
        UUID id,
        TemplateKey templateKey,
        int version,
        String versionCode,
        ProjectType projectType,
        String displayName,
        Lifecycle lifecycle,
        long rowVersion,
        Instant publishedAt,
        Instant retiredAt,
        List<ContentBlueprint> contentBlueprints,
        List<WorkflowStatus> statuses,
        List<WorkflowTransition> transitions
) {

    public ProjectTemplateDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(templateKey, "templateKey must not be null");
        if (version <= 0 || rowVersion < 0) {
            throw new IllegalArgumentException("version must be positive and rowVersion non-negative");
        }
        versionCode = requireText(versionCode, "versionCode");
        if (!versionCode.equals(templateKey.name() + "_V" + version)) {
            throw new IllegalArgumentException("versionCode must match templateKey and version");
        }
        Objects.requireNonNull(projectType, "projectType must not be null");
        displayName = requireText(displayName, "displayName");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        contentBlueprints = List.copyOf(contentBlueprints);
        statuses = List.copyOf(statuses);
        transitions = List.copyOf(transitions);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be trimmed and non-blank");
        }
        return value;
    }

    public enum TemplateKey {
        RND,
        PRE_SALES,
        IMPLEMENTATION,
        HYPERCARE
    }

    public enum ProjectType {
        PRODUCT_DEVELOPMENT,
        PRE_SALES,
        IMPLEMENTATION,
        HYPERCARE
    }

    public enum Lifecycle {
        DRAFT,
        PUBLISHED,
        RETIRED
    }

    public enum StatusCategory {
        TODO,
        IN_PROGRESS,
        DONE,
        CANCELED
    }

    public enum RequiredPermission {
        MEMBER
    }

    public record ContentBlueprint(
            String contentCode,
            String displayName,
            String colorToken,
            int sortOrder
    ) {
        public ContentBlueprint {
            contentCode = requireText(contentCode, "contentCode");
            displayName = requireText(displayName, "displayName");
            colorToken = requireText(colorToken, "colorToken");
            if (sortOrder <= 0) {
                throw new IllegalArgumentException("sortOrder must be positive");
            }
        }
    }

    public record WorkflowStatus(
            String statusCode,
            String displayName,
            StatusCategory statusCategory,
            int sortOrder,
            boolean initial,
            boolean terminal
    ) {
        public WorkflowStatus {
            statusCode = requireText(statusCode, "statusCode");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(statusCategory, "statusCategory must not be null");
            if (sortOrder <= 0) {
                throw new IllegalArgumentException("sortOrder must be positive");
            }
        }
    }

    public record WorkflowTransition(
            String fromStatus,
            String toStatus,
            RequiredPermission requiredPermission,
            boolean requiresResolution
    ) {
        public WorkflowTransition {
            fromStatus = requireText(fromStatus, "fromStatus");
            toStatus = requireText(toStatus, "toStatus");
            Objects.requireNonNull(requiredPermission, "requiredPermission must not be null");
            if (fromStatus.equals(toStatus)) {
                throw new IllegalArgumentException("transition endpoints must differ");
            }
        }
    }
}
