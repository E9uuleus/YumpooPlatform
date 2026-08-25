package com.yumpoo.platform.filestorage.api;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public final class AttachmentModels {
    private AttachmentModels() {}

    public record AttachmentCapabilities(boolean canUploadContent) {}

    public record AttachmentMetadata(
            UUID id,
            UUID companyId,
            UUID projectId,
            AttachmentOwnerType ownerType,
            UUID ownerId,
            String originalFileName,
            String declaredMime,
            String detectedMime,
            Long sizeBytes,
            AttachmentStatus status,
            AttachmentRejectedCode rejectedCode,
            UUID uploadedByUserId,
            Instant createdAt,
            Instant availableAt,
            Instant expiresAt,
            long rowVersion,
            String etag,
            AttachmentCapabilities capabilities
    ) {}

    public record AttachmentIntentResult(
            String uploadUrl,
            Instant expiresAt,
            long maxBytes,
            AttachmentMetadata metadata
    ) {}

    public record AttachmentPage(List<AttachmentMetadata> items, String nextCursor) {
        public AttachmentPage {
            items = List.copyOf(items);
        }
    }

    public record CreateIntent(
            UUID id,
            UUID companyId,
            UUID projectId,
            AttachmentOwnerType ownerType,
            UUID ownerId,
            String originalFileName,
            String declaredMime,
            Long sizeBytes,
            UUID uploadedByUserId,
            Instant now
    ) {}

    public record UploadContent(
            UUID companyId,
            UUID attachmentId,
            InputStream content,
            OptionalLong contentLength,
            Instant now
    ) {
        public UploadContent {
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(contentLength, "contentLength must not be null");
        }
    }

    public record ScanClaim(
            UUID taskId,
            UUID leaseToken,
            UUID attachmentId,
            UUID companyId,
            UUID projectId,
            AttachmentOwnerType ownerType,
            UUID ownerId,
            UUID uploadedByUserId,
            String originalFileName,
            String declaredMime,
            long sizeBytes,
            String sha256,
            String detectedMime,
            String storageKey,
            int generation,
            int attemptCount
    ) {}

    public sealed interface ScanOutcome {
        record Clean(String detectedMime, String storageKey) implements ScanOutcome {}
        record Rejected(AttachmentRejectedCode code) implements ScanOutcome {}
        record Unavailable() implements ScanOutcome {}
    }

    public record Finalization(
            UUID attachmentId,
            UUID companyId,
            UUID projectId,
            AttachmentOwnerType ownerType,
            UUID ownerId,
            UUID uploadedByUserId,
            String originalFileName,
            String detectedMime,
            long sizeBytes,
            String storageKey,
            int generation,
            UUID taskId,
            UUID leaseToken
    ) {}

    public record RescanResult(UUID attachmentId, AttachmentStatus status, int generation,
            long rowVersion, String etag) {}
}
