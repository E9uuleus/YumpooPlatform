package com.yumpoo.platform.filestorage.testing;

import com.yumpoo.platform.filestorage.api.AttachmentStatus;
import com.yumpoo.platform.filestorage.application.AttachmentFileName;
import com.yumpoo.platform.filestorage.application.AttachmentFileNamePolicy;
import com.yumpoo.platform.filestorage.application.AttachmentProcessingOutcome;
import com.yumpoo.platform.filestorage.application.AttachmentSafetyProcessor;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.application.UploadIncompleteException;
import com.yumpoo.platform.filestorage.application.UploadRejectedException;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** M0-14 HTTP/PostgreSQL 探针编排，不属于正式附件业务 API。 */
public final class M014AttachmentProbeService {

    private final M014AttachmentProbeRepository repository;
    private final M014ParentAccessResolver accessResolver;
    private final QuarantineStorage storage;
    private final AttachmentSafetyProcessor safetyProcessor;
    private final ExecutorService executor;
    private final Map<UUID, CompletableFuture<Void>> processing = new ConcurrentHashMap<>();

    public M014AttachmentProbeService(
            M014AttachmentProbeRepository repository,
            M014ParentAccessResolver accessResolver,
            QuarantineStorage storage,
            AttachmentSafetyProcessor safetyProcessor,
            ExecutorService executor
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.accessResolver = Objects.requireNonNull(
                accessResolver,
                "accessResolver must not be null"
        );
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.safetyProcessor = Objects.requireNonNull(
                safetyProcessor,
                "safetyProcessor must not be null"
        );
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public Metadata createIntent(
            UUID ownerId,
            UUID actorId,
            String originalFileName,
            String declaredMime
    ) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (!accessResolver.canWrite(ownerId, actorId)) {
            throw notFound();
        }
        AttachmentFileName fileName;
        try {
            fileName = AttachmentFileNamePolicy.normalize(originalFileName);
        } catch (UploadRejectedException exception) {
            throw publicUploadRejection(exception);
        }
        String normalizedMime = normalizeMime(declaredMime);
        if (!fileName.expectedType().acceptsDeclaredMime(normalizedMime)) {
            throw new ApplicationException(StandardErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        UUID attachmentId = repository.createIntent(
                ownerId,
                actorId,
                fileName.displayName(),
                normalizedMime
        );
        return metadata(repository.find(attachmentId).orElseThrow());
    }

    public Metadata receive(
            UUID attachmentId,
            UUID actorId,
            InputStream source,
            OptionalLong contentLength
    ) {
        Objects.requireNonNull(source, "source must not be null");
        M014AttachmentProbeRepository.ProbeRow row = visibleRow(attachmentId, actorId, true);
        if (!actorId.equals(row.uploaderActor()) || !repository.claimReceive(attachmentId, actorId)) {
            throw new ApplicationException(StandardErrorCode.REQUEST_IN_PROGRESS);
        }

        SealedUpload upload;
        try {
            upload = storage.receive(attachmentId, source, contentLength);
        } catch (UploadRejectedException exception) {
            repository.rejectReceive(attachmentId, exception.rejectedCode());
            throw publicUploadRejection(exception);
        } catch (UploadIncompleteException exception) {
            repository.resetIncomplete(attachmentId);
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        } catch (IOException exception) {
            repository.resetIncomplete(attachmentId);
            throw new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
        }

        try {
            repository.queueForScan(attachmentId, upload);
        } catch (RuntimeException exception) {
            storage.discard(upload);
            repository.resetIncomplete(attachmentId);
            throw exception;
        }

        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> process(attachmentId),
                    executor
            );
            processing.put(attachmentId, future);
        } catch (RejectedExecutionException exception) {
            repository.finalizeRejected(
                    attachmentId,
                    com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode.SCAN_UNAVAILABLE,
                    true
            );
            throw new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return metadata(repository.find(attachmentId).orElseThrow());
    }

    public Metadata findMetadata(UUID attachmentId, UUID actorId) {
        return metadata(visibleRow(attachmentId, actorId, false));
    }

    public Download openDownload(UUID attachmentId, UUID actorId) {
        M014AttachmentProbeRepository.ProbeRow row = visibleRow(
                attachmentId,
                actorId,
                false
        );
        if (row.status() != AttachmentStatus.AVAILABLE) {
            throw notFound();
        }
        try {
            if (!storage.verify(row.publishedBlob())) {
                throw notFound();
            }
            return new Download(
                    storage.open(row.publishedBlob()),
                    row.originalFileName(),
                    row.detectedMime(),
                    row.sizeBytes(),
                    row.sha256()
            );
        } catch (IOException exception) {
            throw notFound();
        }
    }

    public void awaitProcessing(UUID attachmentId, Duration timeout)
            throws InterruptedException, TimeoutException {
        CompletableFuture<Void> future = processing.get(attachmentId);
        if (future == null) {
            throw new IllegalStateException("M0-14 attachment has no async processing task");
        }
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            throw new CompletionException(exception.getCause());
        }
    }

    public void awaitAll(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (CompletableFuture<Void> future : processing.values()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
                // 清理阶段只等待任务退出；测试本身在对应断言处检查失败原因。
            }
        }
        processing.clear();
    }

    private void process(UUID attachmentId) {
        M014AttachmentProbeRepository.ProbeRow row = repository.find(attachmentId)
                .orElseThrow();
        repository.markScanning(attachmentId);
        AttachmentFileName fileName = AttachmentFileNamePolicy.normalize(row.originalFileName());

        AttachmentProcessingOutcome outcome;
        try {
            outcome = safetyProcessor.process(
                    row.sealedUpload(),
                    fileName,
                    row.declaredMime(),
                    () -> accessResolver.canWrite(row.ownerId(), row.uploaderActor())
            );
        } catch (RuntimeException exception) {
            repository.finalizeRejected(
                    attachmentId,
                    com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode.SCAN_UNAVAILABLE,
                    true
            );
            throw exception;
        }

        if (outcome instanceof AttachmentProcessingOutcome.Available available) {
            repository.markFinalizing(attachmentId);
            repository.finalizeAvailable(
                    attachmentId,
                    available.blob(),
                    available.detectedContent().detectedMime()
            );
            return;
        }
        AttachmentProcessingOutcome.Rejected rejected =
                (AttachmentProcessingOutcome.Rejected) outcome;
        repository.finalizeRejected(
                attachmentId,
                rejected.rejectedCode(),
                rejected.quarantinedContentRetained()
        );
    }

    private M014AttachmentProbeRepository.ProbeRow visibleRow(
            UUID attachmentId,
            UUID actorId,
            boolean requireWrite
    ) {
        Objects.requireNonNull(attachmentId, "attachmentId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        M014AttachmentProbeRepository.ProbeRow row = repository.find(attachmentId)
                .orElseThrow(M014AttachmentProbeService::notFound);
        boolean allowed = requireWrite
                ? accessResolver.canWrite(row.ownerId(), actorId)
                : accessResolver.canRead(row.ownerId(), actorId);
        if (!allowed) {
            throw notFound();
        }
        return row;
    }

    private static Metadata metadata(M014AttachmentProbeRepository.ProbeRow row) {
        return new Metadata(
                row.id(),
                row.ownerId(),
                row.originalFileName(),
                row.declaredMime(),
                row.status(),
                row.processingStage() == null ? null : row.processingStage().name(),
                row.sizeBytes(),
                row.sha256(),
                row.detectedMime(),
                row.rejectedCode() == null ? null : row.rejectedCode().name(),
                row.lastFailureCode() == null ? null : row.lastFailureCode().name(),
                row.rowVersion()
        );
    }

    private static String normalizeMime(String declaredMime) {
        if (declaredMime == null || declaredMime.isBlank()) {
            throw new ApplicationException(StandardErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        String normalized = declaredMime.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 127 || normalized.indexOf(';') >= 0) {
            throw new ApplicationException(StandardErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return normalized;
    }

    private static ApplicationException publicUploadRejection(
            UploadRejectedException exception
    ) {
        StandardErrorCode code = switch (exception.rejectedCode()) {
            case FILE_TOO_LARGE -> StandardErrorCode.FILE_TOO_LARGE;
            default -> StandardErrorCode.FILE_TYPE_NOT_ALLOWED;
        };
        return new ApplicationException(code);
    }

    private static ApplicationException notFound() {
        return new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
    }

    public record Metadata(
            UUID id,
            UUID ownerId,
            String fileName,
            String declaredMime,
            AttachmentStatus status,
            String processingStage,
            Long sizeBytes,
            String sha256,
            String detectedMime,
            String rejectedCode,
            String lastFailureCode,
            long rowVersion
    ) {
    }

    public record Download(
            InputStream inputStream,
            String fileName,
            String detectedMime,
            long sizeBytes,
            String sha256
    ) {
        public Download {
            Objects.requireNonNull(inputStream, "inputStream must not be null");
        }
    }
}
