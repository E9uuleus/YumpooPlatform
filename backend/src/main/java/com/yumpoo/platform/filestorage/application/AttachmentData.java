package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public final class AttachmentData {
    private AttachmentData() {}
    public record CreateIntent(UUID id, UUID companyId, UUID projectId,
            AttachmentOwnerType ownerType, UUID ownerId, String originalFileName,
            String declaredMime, Long sizeBytes, UUID uploadedByUserId, Instant now) {}
    public record UploadContent(UUID companyId, UUID attachmentId, InputStream content,
            OptionalLong contentLength, Instant now) {}
    public record ScanClaim(UUID taskId, UUID leaseToken, UUID attachmentId, UUID companyId,
            UUID projectId, AttachmentOwnerType ownerType, UUID ownerId, UUID uploadedByUserId,
            String originalFileName, String declaredMime, long sizeBytes, String sha256,
            String detectedMime, String storageKey, int generation, int attemptCount) {}
    public sealed interface ScanOutcome {
        record Clean(String detectedMime,String storageKey) implements ScanOutcome {}
        record Rejected(AttachmentRejectedCode code) implements ScanOutcome {}
        record Unavailable() implements ScanOutcome {}
    }
    public record Finalization(UUID attachmentId, UUID companyId, UUID projectId,
            AttachmentOwnerType ownerType, UUID ownerId, UUID uploadedByUserId,
            String originalFileName, String detectedMime, long sizeBytes, String storageKey,
            int generation, UUID taskId, UUID leaseToken) {}
    public record RescanResult(UUID attachmentId, String status, int generation,
            long rowVersion, String etag) {}
    public record IntentResult(String uploadUrl, Instant expiresAt, long maxBytes,
            AttachmentRecord metadata) {}
    public record Page(List<AttachmentRecord> items,String nextCursor) {}
}
