package com.yumpoo.platform.filestorage.api;

import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentIntentResult;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentMetadata;
import com.yumpoo.platform.filestorage.api.AttachmentModels.AttachmentPage;
import com.yumpoo.platform.filestorage.api.AttachmentModels.CreateIntent;
import com.yumpoo.platform.filestorage.api.AttachmentModels.Finalization;
import com.yumpoo.platform.filestorage.api.AttachmentModels.RescanResult;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanClaim;
import com.yumpoo.platform.filestorage.api.AttachmentModels.ScanOutcome;
import com.yumpoo.platform.filestorage.api.AttachmentModels.UploadContent;
import com.yumpoo.platform.filestorage.api.AttachmentModels.DownloadContent;
import com.yumpoo.platform.filestorage.api.AttachmentModels.DeleteResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentLifecyclePort {
    AttachmentIntentResult createIntent(CreateIntent command);
    AttachmentMetadata upload(UploadContent command);
    DownloadContent download(UUID companyId, UUID attachmentId, Instant now);
    DeleteResult delete(UUID companyId, UUID attachmentId, UUID deletedByUserId,
            String reason, long expectedVersion, Instant now);
    Optional<AttachmentMetadata> find(UUID companyId, UUID attachmentId, Instant now);
    AttachmentPage list(UUID companyId, AttachmentOwnerType ownerType, UUID ownerId,
            String cursor, int size, Instant now);
    Optional<ScanClaim> claimDue(String workerId, Instant now);
    ScanOutcome scan(ScanClaim claim);
    Optional<Finalization> prepareFinalization(ScanClaim claim, ScanOutcome.Clean clean, Instant now);
    AttachmentMetadata completeAvailable(Finalization finalization, Instant now);
    void completeRejected(ScanClaim claim, AttachmentRejectedCode code, Instant now);
    void retryOrExhaust(ScanClaim claim, Instant now);
    RescanResult rescan(UUID companyId, UUID attachmentId, long expectedVersion, Instant now);
}
