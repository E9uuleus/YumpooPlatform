package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkItemRelationModels {
    private WorkItemRelationModels() {}

    public record Counterpart(UUID id, UUID projectId, UUID contentId, String itemNo,
            String contentName, String contentColorToken, String title,
            String statusCode, boolean deleted) {}

    public record Capabilities(boolean canDelete, boolean canChangeParent) {}

    public record RelationView(UUID id, String relationType, String currentRole,
            String counterpartRole, boolean counterpartVisible, Counterpart counterpart,
            String status, UUID createdByUserId, Instant createdAt,
            UUID deletedByUserId, Instant deletedAt, String deleteReason,
            long rowVersion, String etag, Capabilities capabilities) {}

    public record RelationPage(List<RelationView> items, int page, int size,
            long totalElements, int totalPages, boolean canCreate,
            boolean hasHiddenRelations) {
        public RelationPage { items = List.copyOf(items); }
    }

    public record ActiveParent(UUID relationId, String etag, Counterpart parent) {}

    public record Candidate(Counterpart item, String eligibility, String reasonCode,
            ActiveParent activeParent) {}

    public record CandidatePage(List<Candidate> items, int page, int size,
            long totalElements, int totalPages) {
        public CandidatePage { items = List.copyOf(items); }
    }
}
