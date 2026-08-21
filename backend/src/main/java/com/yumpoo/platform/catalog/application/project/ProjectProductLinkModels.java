package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectProductLink;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProjectProductLinkModels {
    private ProjectProductLinkModels() {}

    public record LinkView(
            UUID id,
            UUID projectId,
            UUID productId,
            String productCode,
            String productName,
            String productStatus,
            String relationType,
            boolean isPrimary,
            String status,
            Instant linkedAt,
            UUID linkedByUserId,
            Instant updatedAt,
            UUID updatedByUserId,
            Instant removedAt,
            UUID removedByUserId,
            String removeReason,
            long rowVersion,
            String etag
    ) {}

    public record LinkProjection(
            ProjectProductLink link,
            String productCode,
            String productName,
            String productStatus
    ) {}

    public record LinkList(List<LinkView> items) {}

    public record ProductCandidate(
            UUID id,
            String code,
            String name,
            List<String> activeRelationTypes,
            boolean primary
    ) {}

    public record ProductCandidatePage(
            List<ProductCandidate> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
