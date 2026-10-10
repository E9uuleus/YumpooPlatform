package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 在事务外识别和落位；调用方只在返回后执行最终短事务。
 */
public final class AttachmentSafetyProcessor {

    private final QuarantineStorage storage;
    private final AttachmentContentDetector detector;

    public AttachmentSafetyProcessor(
            QuarantineStorage storage,
            AttachmentContentDetector detector
    ) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
    }

    public AttachmentProcessingOutcome process(
            SealedUpload upload,
            AttachmentFileName fileName,
            String declaredMime,
            BooleanSupplier parentWritable
    ) {
        Objects.requireNonNull(upload, "upload must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(parentWritable, "parentWritable must not be null");

        DetectedAttachmentContent detected;
        try {
            detected = detector.detect(upload.quarantinedPath(), fileName);
        } catch (UploadRejectedException exception) {
            storage.discard(upload);
            return rejected(exception.rejectedCode(), false, false);
        } catch (IOException exception) {
            storage.discard(upload);
            return rejected(AttachmentRejectedCode.INTEGRITY_CHECK_FAILED, false, false);
        }
        if (detected.fileType() != fileName.expectedType()
                || !fileName.expectedType().acceptsDeclaredMime(declaredMime)) {
            storage.discard(upload);
            return rejected(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED, false, false);
        }

        PublishedBlob published;
        try {
            published = storage.publish(upload);
        } catch (IOException exception) {
            return rejected(
                    AttachmentRejectedCode.INTEGRITY_CHECK_FAILED,
                    true,
                    false
            );
        }

        boolean writable;
        try {
            writable = parentWritable.getAsBoolean();
        } catch (RuntimeException exception) {
            writable = false;
        }
        if (!writable) {
            return rejected(AttachmentRejectedCode.PARENT_NOT_WRITABLE, false, true);
        }
        try {
            if (!storage.verify(published)) {
                return rejected(AttachmentRejectedCode.INTEGRITY_CHECK_FAILED, false, true);
            }
        } catch (IOException exception) {
            return rejected(AttachmentRejectedCode.INTEGRITY_CHECK_FAILED, false, true);
        }
        return new AttachmentProcessingOutcome.Available(published, detected);
    }

    private static AttachmentProcessingOutcome.Rejected rejected(
            AttachmentRejectedCode code,
            boolean quarantinedRetained,
            boolean orphanRetained
    ) {
        return new AttachmentProcessingOutcome.Rejected(code, quarantinedRetained, orphanRetained);
    }
}
