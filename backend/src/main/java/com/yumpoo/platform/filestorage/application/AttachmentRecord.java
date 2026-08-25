package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import com.yumpoo.platform.filestorage.domain.AttachmentState;

import java.time.Instant;
import java.util.UUID;

public record AttachmentRecord(
        UUID id, UUID companyId, UUID projectId, AttachmentOwnerType ownerType, UUID ownerId,
        String originalFileName, String fileExtension, String declaredMime, String detectedMime,
        Long sizeBytes, String sha256, String storageKey, AttachmentState status,
        String processingStage, AttachmentRejectedCode rejectedCode, long reservedBytes,
        UUID uploadedByUserId, Instant intentExpiresAt, Instant quarantineRetainUntil,
        Instant availableAt, UUID uploadLeaseToken, Instant uploadLeaseUntil,
        UUID deletedByUserId, Instant deletedAt, String deleteReason,
        int scanGeneration, long rowVersion, Instant createdAt
) {}
