package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.application.AttachmentData.CreateIntent;
import com.yumpoo.platform.filestorage.application.AttachmentData.Finalization;
import com.yumpoo.platform.filestorage.application.AttachmentData.RescanResult;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanClaim;
import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository {
    AttachmentRecord insertIntent(CreateIntent command, AttachmentFileName fileName,
            long reservedBytes, long companyLimit, long projectLimit, Instant expiresAt);
    Optional<AttachmentRecord> find(UUID companyId, UUID attachmentId);
    List<AttachmentRecord> list(UUID companyId, AttachmentOwnerType ownerType, UUID ownerId,
            Instant beforeCreatedAt, UUID beforeId, int limit);
    Optional<AttachmentRecord> beginUpload(UUID companyId, UUID attachmentId, UUID leaseToken,
            Instant now, Instant leaseUntil);
    AttachmentRecord seal(UUID companyId, UUID attachmentId, UUID leaseToken,
            long sizeBytes, String sha256, Instant now);
    void cancelUpload(UUID companyId, UUID attachmentId, UUID leaseToken, Instant now);
    AttachmentRecord rejectUpload(UUID companyId, UUID attachmentId, UUID leaseToken,
            AttachmentRejectedCode code, Instant now);
    Optional<ScanClaim> claimDue(String workerId, UUID leaseToken, Instant now, Instant leaseUntil);
    void recordDetected(ScanClaim claim, String detectedMime, Instant now);
    void recordPublished(ScanClaim claim, String storageKey, Instant now);
    Boolean claimPublish(ScanClaim claim,String storageKey,String owner,UUID operationToken,
            Instant now,Instant leaseUntil);
    void completePublish(String storageKey,UUID operationToken,Instant now);
    void releasePublish(String storageKey,UUID operationToken,Instant now);
    Optional<Finalization> prepareFinalization(ScanClaim claim, String detectedMime,
            String storageKey, Instant now);
    AttachmentRecord completeAvailable(Finalization finalization, Instant now);
    void completeRejected(ScanClaim claim, AttachmentRejectedCode code, Instant now,
            Instant retainUntil);
    void retry(ScanClaim claim, Instant nextAttemptAt, Instant now);
    RescanResult rescan(UUID companyId, UUID attachmentId, long expectedVersion,
            long companyLimit, long projectLimit, Instant now);
    AttachmentRecord delete(UUID companyId, UUID attachmentId, UUID deletedByUserId,
            String reason, long expectedVersion, Instant now);
    void recordReconciliationIssue(String issueCode, String subjectType, String subjectKey,
            UUID attachmentId, UUID companyId, Instant now);
    void resolveReconciliationIssues(String subjectType, String subjectKey, Instant now);
}
