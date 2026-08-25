package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.application.AttachmentData.CreateIntent;
import com.yumpoo.platform.filestorage.application.AttachmentData.Finalization;
import com.yumpoo.platform.filestorage.application.AttachmentData.IntentResult;
import com.yumpoo.platform.filestorage.application.AttachmentData.Page;
import com.yumpoo.platform.filestorage.application.AttachmentData.RescanResult;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanClaim;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanOutcome;
import com.yumpoo.platform.filestorage.application.AttachmentData.UploadContent;
import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import com.yumpoo.platform.filestorage.domain.AttachmentState;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttachmentLifecycleService {
    private static final Duration INTENT_LIFETIME = Duration.ofHours(24);
    private static final Duration QUARANTINE_RETENTION = Duration.ofHours(24);

    private final AttachmentRepository repository;
    private final QuarantineStorage storage;
    private final AttachmentContentDetector detector;
    private final MalwareScanner scanner;
    private final AttachmentRuntimeSettings settings;

    public AttachmentLifecycleService(AttachmentRepository repository, QuarantineStorage storage,
            AttachmentContentDetector detector, MalwareScanner scanner,
            AttachmentRuntimeSettings settings) {
        this.repository = repository;
        this.storage = storage;
        this.detector = detector;
        this.scanner = scanner;
        this.settings = settings;
    }

    public IntentResult createIntent(CreateIntent command) {
        if (command.ownerType() == AttachmentOwnerType.PRODUCT_FEEDBACK
                || command.ownerType() == AttachmentOwnerType.FEEDBACK_UPDATE) {
            throw validation("ownerType", "OWNER_TYPE_NOT_AVAILABLE", "该附件归属类型将在后续里程碑开放");
        }
        if (command.declaredMime() == null || command.declaredMime().isBlank()
                || command.declaredMime().length() > 160) {
            throw validation("declaredMime", "INVALID_MIME", "声明 MIME 无效");
        }
        AttachmentFileName fileName;
        try {
            fileName = AttachmentFileNamePolicy.normalize(command.originalFileName());
        } catch (UploadRejectedException exception) {
            throw new ApplicationException(StandardErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        long reserved = command.sizeBytes() == null ? AttachmentUploadPolicy.MAX_BYTES : command.sizeBytes();
        if (reserved < 0 || reserved > AttachmentUploadPolicy.MAX_BYTES) {
            throw validation("sizeBytes", "OUT_OF_RANGE", "文件大小必须在 0 到 104857600 之间");
        }
        Instant expiresAt = command.now().plus(INTENT_LIFETIME);
        AttachmentRecord record = repository.insertIntent(command, fileName, reserved,
                settings.companyQuotaBytes(), settings.projectQuotaBytes(), expiresAt);
        return new IntentResult("/api/v1/attachments/" + record.id() + "/content",
                expiresAt, AttachmentUploadPolicy.MAX_BYTES, record);
    }

    public AttachmentRecord upload(UploadContent command) {
        UUID leaseToken = UUID.randomUUID();
        AttachmentRecord claimed = repository.beginUpload(command.companyId(), command.attachmentId(),
                        leaseToken, command.now(), command.now().plus(settings.uploadLease()))
                .orElseThrow(() -> invalid("ATTACHMENT_NOT_UPLOADABLE"));
        try {
            SealedUpload sealed = storage.receive(command.attachmentId(), command.content(),
                    command.contentLength(), claimed.reservedBytes());
            return repository.seal(command.companyId(), command.attachmentId(), leaseToken,
                    sealed.sizeBytes(), sealed.sha256(), Instant.now());
        } catch (UploadRejectedException exception) {
            AttachmentRejectedCode code = AttachmentRejectedCode.valueOf(exception.rejectedCode().name());
            repository.rejectUpload(command.companyId(), command.attachmentId(), leaseToken, code, Instant.now());
            if (code == AttachmentRejectedCode.FILE_TOO_LARGE) {
                throw new ApplicationException(StandardErrorCode.FILE_TOO_LARGE);
            }
            if (code == AttachmentRejectedCode.QUOTA_EXCEEDED) {
                throw validation("content", "QUOTA_EXCEEDED", "上传字节超过本意图预约量");
            }
            throw new ApplicationException(StandardErrorCode.FILE_TYPE_NOT_ALLOWED);
        } catch (IOException exception) {
            repository.cancelUpload(command.companyId(), command.attachmentId(), leaseToken, Instant.now());
            throw invalid("UPLOAD_INCOMPLETE");
        }
    }

    public AttachmentBoundaryData.Download downloadBoundary(UUID companyId, UUID attachmentId,
            Instant now) {
        AttachmentRecord row = repository.find(companyId, attachmentId)
                .filter(value -> value.status() == AttachmentState.AVAILABLE)
                .orElseThrow(AttachmentLifecycleService::notFound);
        PublishedBlob blob = new PublishedBlob(row.storageKey(), row.sizeBytes(), row.sha256());
        try {
            BlobVerification verification = storage.inspect(blob);
            if (verification != BlobVerification.VERIFIED) {
                repository.recordReconciliationIssue(verification.name(), "ATTACHMENT",
                        row.id().toString(), row.id(), row.companyId(), now);
                throw dependencyUnavailable();
            }
            repository.resolveReconciliationIssues("ATTACHMENT", row.id().toString(), now);
            return new AttachmentBoundaryData.Download(storage.open(blob), row.originalFileName(),
                    row.detectedMime(), row.sizeBytes());
        } catch (IOException exception) {
            repository.recordReconciliationIssue("MISSING_BLOB", "ATTACHMENT",
                    row.id().toString(), row.id(), row.companyId(), now);
            throw dependencyUnavailable();
        }
    }

    public AttachmentBoundaryData.Deleted deleteBoundary(AttachmentBoundaryData.Delete value) {
        String reason = value.reason() == null ? "" : value.reason().strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw validation("reason", "INVALID_LENGTH", "删除理由长度必须在 1 到 500 之间");
        }
        AttachmentRecord before = repository.find(value.companyId(), value.attachmentId())
                .orElseThrow(AttachmentLifecycleService::notFound);
        AttachmentRecord after = repository.delete(value.companyId(), value.attachmentId(),
                value.deletedByUserId(), reason, value.expectedVersion(), value.now());
        return new AttachmentBoundaryData.Deleted(after.id(), after.status().name(),
                after.deletedByUserId(), after.deletedAt(), after.deleteReason(), before.rowVersion(),
                after.rowVersion(), com.yumpoo.platform.foundation.application.concurrency.StrongEtag
                        .format(after.rowVersion()));
    }

    public Optional<AttachmentRecord> find(UUID companyId, UUID attachmentId, Instant now) {
        return repository.find(companyId, attachmentId);
    }

    public Page list(UUID companyId, AttachmentOwnerType ownerType, UUID ownerId,
            String cursor, int size, Instant now) {
        if (size < 1 || size > 100) throw validation("size", "OUT_OF_RANGE", "分页大小必须在 1 到 100 之间");
        Cursor decoded = decode(cursor);
        List<AttachmentRecord> rows = repository.list(companyId, ownerType, ownerId,
                decoded == null ? null : decoded.createdAt(), decoded == null ? null : decoded.id(), size + 1);
        boolean more = rows.size() > size;
        if (more) rows = new ArrayList<>(rows.subList(0, size));
        String next = more && !rows.isEmpty() ? encode(rows.getLast()) : null;
        return new Page(rows, next);
    }

    public Optional<ScanClaim> claimDue(String workerId, Instant now) {
        return repository.claimDue(workerId, UUID.randomUUID(), now, now.plus(settings.scanLease()));
    }

    public ScanOutcome scan(ScanClaim claim) {
        try {
            SealedUpload upload = storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256());
            String detectedMime = claim.detectedMime();
            if (detectedMime == null) {
                AttachmentFileName fileName = AttachmentFileNamePolicy.normalize(claim.originalFileName());
                DetectedAttachmentContent detected = detector.detect(upload.quarantinedPath(), fileName);
                if (detected.fileType() != fileName.expectedType()
                        || !fileName.expectedType().acceptsDeclaredMime(claim.declaredMime())) {
                    storage.discard(upload);
                    return new ScanOutcome.Rejected(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED);
                }
                detectedMime = detected.detectedMime();
                MalwareScanVerdict verdict;
                try {
                    verdict = scanner.scan(upload.quarantinedPath());
                } catch (RuntimeException exception) {
                    verdict = MalwareScanVerdict.UNAVAILABLE;
                }
                if (verdict == MalwareScanVerdict.UNAVAILABLE) return new ScanOutcome.Unavailable();
                if (verdict == MalwareScanVerdict.THREAT_DETECTED) {
                    storage.discard(upload);
                    return new ScanOutcome.Rejected(AttachmentRejectedCode.MALWARE_DETECTED);
                }
                // detected_mime 同时是本代扫描已通过的持久检查点。必须在查毒成功后写入，
                // 否则 SCAN_UNAVAILABLE 重试会误把仅完成类型探测的内容当作安全内容发布。
                repository.recordDetected(claim, detectedMime, Instant.now());
            }
            PublishedBlob published;
            if (claim.storageKey() != null) {
                published = new PublishedBlob(claim.storageKey(), claim.sizeBytes(), claim.sha256());
                if (!storage.verify(published)) {
                    return new ScanOutcome.Rejected(AttachmentRejectedCode.INTEGRITY_CHECK_FAILED);
                }
            } else {
                String expectedKey=storageKey(claim.sha256());
                UUID operationToken=UUID.randomUUID(); Instant leaseNow=Instant.now();
                if(Boolean.FALSE.equals(repository.claimPublish(claim,expectedKey,"scan:"+claim.taskId(),operationToken,
                        leaseNow,leaseNow.plus(settings.scanLease())))) {
                    return new ScanOutcome.Unavailable();
                }
                try {
                    published = storage.publish(upload);
                    repository.recordPublished(claim, published.storageKey(), Instant.now());
                    repository.completePublish(published.storageKey(),operationToken,Instant.now());
                } catch(IOException|RuntimeException failure) {
                    repository.releasePublish(expectedKey,operationToken,Instant.now());
                    throw failure;
                }
            }
            return new ScanOutcome.Clean(detectedMime, published.storageKey());
        } catch (UploadRejectedException exception) {
            return new ScanOutcome.Rejected(AttachmentRejectedCode.valueOf(exception.rejectedCode().name()));
        } catch (IOException | RuntimeException exception) {
            return new ScanOutcome.Rejected(AttachmentRejectedCode.INTEGRITY_CHECK_FAILED);
        }
    }

    public Optional<Finalization> prepareFinalization(ScanClaim claim, ScanOutcome.Clean clean,
            Instant now) {
        return repository.prepareFinalization(claim, clean.detectedMime(), clean.storageKey(), now);
    }

    public AttachmentRecord completeAvailable(Finalization finalization, Instant now) {
        return repository.completeAvailable(finalization, now);
    }

    public void completeRejected(ScanClaim claim, AttachmentRejectedCode code, Instant now) {
        discardSealed(claim);
        repository.completeRejected(claim, code, now, null);
    }

    public void retryOrExhaust(ScanClaim claim, Instant now) {
        if (claim.attemptCount() < 3) {
            Duration delay = claim.attemptCount() == 1
                    ? settings.firstScanRetry() : settings.secondScanRetry();
            repository.retry(claim, now.plus(delay), now);
        } else {
            repository.completeRejected(claim, AttachmentRejectedCode.SCAN_UNAVAILABLE, now,
                    now.plus(QUARANTINE_RETENTION));
        }
    }

    public RescanResult rescan(UUID companyId, UUID attachmentId, long expectedVersion, Instant now) {
        return repository.rescan(companyId, attachmentId, expectedVersion,
                settings.companyQuotaBytes(), settings.projectQuotaBytes(), now);
    }

    public AttachmentBoundaryData.Intent createBoundary(AttachmentBoundaryData.Create value) {
        IntentResult result=createIntent(new CreateIntent(value.id(),value.companyId(),value.projectId(),
                AttachmentOwnerType.valueOf(value.ownerType()),value.ownerId(),value.originalFileName(),
                value.declaredMime(),value.sizeBytes(),value.uploadedByUserId(),value.now()));
        return new AttachmentBoundaryData.Intent(result.uploadUrl(),result.expiresAt(),result.maxBytes(),
                boundary(result.metadata(),value.now()));
    }

    public AttachmentBoundaryData.Record uploadBoundary(AttachmentBoundaryData.Upload value) {
        return boundary(upload(new UploadContent(value.companyId(),value.attachmentId(),value.content(),
                value.contentLength(),value.now())),Instant.now());
    }

    public Optional<AttachmentBoundaryData.Record> findBoundary(UUID companyId,UUID id,Instant now) {
        return find(companyId,id,now).map(value->boundary(value,now));
    }

    public AttachmentBoundaryData.Page listBoundary(UUID companyId,String ownerType,UUID ownerId,
            String cursor,int size,Instant now) {
        Page page=list(companyId,AttachmentOwnerType.valueOf(ownerType),ownerId,cursor,size,now);
        return new AttachmentBoundaryData.Page(page.items().stream().map(value->boundary(value,now)).toList(),page.nextCursor());
    }

    public Optional<AttachmentBoundaryData.Claim> claimBoundary(String workerId,Instant now) {
        return claimDue(workerId,now).map(AttachmentLifecycleService::boundary);
    }

    public AttachmentBoundaryData.Outcome scanBoundary(AttachmentBoundaryData.Claim value) {
        ScanOutcome outcome=scan(internal(value));
        if(outcome instanceof ScanOutcome.Clean clean)return new AttachmentBoundaryData.Outcome.Clean(clean.detectedMime(),clean.storageKey());
        if(outcome instanceof ScanOutcome.Rejected rejected)return new AttachmentBoundaryData.Outcome.Rejected(rejected.code().name());
        return new AttachmentBoundaryData.Outcome.Unavailable();
    }

    public Optional<AttachmentBoundaryData.Finalize> prepareBoundary(AttachmentBoundaryData.Claim claim,
            AttachmentBoundaryData.Outcome.Clean clean,Instant now) {
        return prepareFinalization(internal(claim),new ScanOutcome.Clean(clean.detectedMime(),clean.storageKey()),now)
                .map(AttachmentLifecycleService::boundary);
    }

    public AttachmentBoundaryData.Record completeAvailableBoundary(AttachmentBoundaryData.Finalize value,Instant now) {
        return boundary(completeAvailable(internal(value),now),now);
    }

    public void completeRejectedBoundary(AttachmentBoundaryData.Claim value,String code,Instant now) {
        completeRejected(internal(value),AttachmentRejectedCode.valueOf(code),now);
    }

    public void retryBoundary(AttachmentBoundaryData.Claim value,Instant now) { retryOrExhaust(internal(value),now); }

    public AttachmentBoundaryData.Rescan rescanBoundary(UUID companyId,UUID id,long version,Instant now) {
        RescanResult result=rescan(companyId,id,version,now);
        return new AttachmentBoundaryData.Rescan(result.attachmentId(),result.status(),result.generation(),result.rowVersion(),result.etag());
    }

    private static AttachmentBoundaryData.Record boundary(AttachmentRecord row,Instant now) {
        boolean canUpload=row.status()==AttachmentState.UPLOADING&&row.processingStage()==null
                &&row.uploadLeaseToken()==null&&row.intentExpiresAt().isAfter(now);
        boolean available=row.status()==AttachmentState.AVAILABLE;
        return new AttachmentBoundaryData.Record(row.id(),row.companyId(),row.projectId(),row.ownerType().name(),
                row.ownerId(),row.originalFileName(),row.declaredMime(),row.detectedMime(),row.sizeBytes(),
                row.status().name(),row.rejectedCode()==null?null:row.rejectedCode().name(),row.uploadedByUserId(),
                row.createdAt(),row.availableAt(),row.intentExpiresAt(),row.rowVersion(),canUpload,
                available,available);
    }
    private static AttachmentBoundaryData.Claim boundary(ScanClaim value) {
        return new AttachmentBoundaryData.Claim(value.taskId(),value.leaseToken(),value.attachmentId(),value.companyId(),
                value.projectId(),value.ownerType().name(),value.ownerId(),value.uploadedByUserId(),value.originalFileName(),
                value.declaredMime(),value.sizeBytes(),value.sha256(),value.detectedMime(),value.storageKey(),value.generation(),value.attemptCount());
    }
    private static ScanClaim internal(AttachmentBoundaryData.Claim value) {
        return new ScanClaim(value.taskId(),value.leaseToken(),value.attachmentId(),value.companyId(),value.projectId(),
                AttachmentOwnerType.valueOf(value.ownerType()),value.ownerId(),value.uploadedByUserId(),value.originalFileName(),
                value.declaredMime(),value.sizeBytes(),value.sha256(),value.detectedMime(),value.storageKey(),value.generation(),value.attemptCount());
    }
    private static AttachmentBoundaryData.Finalize boundary(Finalization value) {
        return new AttachmentBoundaryData.Finalize(value.attachmentId(),value.companyId(),value.projectId(),value.ownerType().name(),
                value.ownerId(),value.uploadedByUserId(),value.originalFileName(),value.detectedMime(),value.sizeBytes(),
                value.storageKey(),value.generation(),value.taskId(),value.leaseToken());
    }
    private static Finalization internal(AttachmentBoundaryData.Finalize value) {
        return new Finalization(value.attachmentId(),value.companyId(),value.projectId(),AttachmentOwnerType.valueOf(value.ownerType()),
                value.ownerId(),value.uploadedByUserId(),value.originalFileName(),value.detectedMime(),value.sizeBytes(),
                value.storageKey(),value.generation(),value.taskId(),value.leaseToken());
    }

    private void discardSealed(ScanClaim claim) {
        try {
            storage.discard(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256()));
        } catch (IOException ignored) {
        }
    }

    private static String encode(AttachmentRecord row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (row.createdAt() + "|" + row.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw validation("cursor", "INVALID_CURSOR", "分页游标无效");
        }
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
    private static ApplicationException invalid(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }
    private static ApplicationException notFound() {
        return new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
    }
    private static ApplicationException dependencyUnavailable() {
        return new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
    }
    private static String storageKey(String sha256) {
        return "sha256/"+sha256.substring(0,2)+"/"+sha256.substring(2,4)+"/"+sha256;
    }
    private record Cursor(Instant createdAt, UUID id) {}
}
