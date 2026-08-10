package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 在事务外识别、扫描和落位；调用方只在返回后执行最终短事务。
 */
public final class AttachmentSafetyProcessor {

    public static final int DEFAULT_MAX_SCAN_ATTEMPTS = 3;

    private final QuarantineStorage storage;
    private final AttachmentContentDetector detector;
    private final MalwareScanner scanner;
    private final int maxScanAttempts;

    public AttachmentSafetyProcessor(
            QuarantineStorage storage,
            AttachmentContentDetector detector,
            MalwareScanner scanner
    ) {
        this(storage, detector, scanner, DEFAULT_MAX_SCAN_ATTEMPTS);
    }

    public AttachmentSafetyProcessor(
            QuarantineStorage storage,
            AttachmentContentDetector detector,
            MalwareScanner scanner,
            int maxScanAttempts
    ) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        if (maxScanAttempts < 1) {
            throw new IllegalArgumentException("maxScanAttempts must be positive");
        }
        this.maxScanAttempts = maxScanAttempts;
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

        MalwareScanVerdict verdict = scanWithFiniteRetry(upload);
        if (verdict == MalwareScanVerdict.THREAT_DETECTED) {
            storage.discard(upload);
            return rejected(AttachmentRejectedCode.MALWARE_DETECTED, false, false);
        }
        if (verdict != MalwareScanVerdict.CLEAN) {
            return rejected(AttachmentRejectedCode.SCAN_UNAVAILABLE, true, false);
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

    private MalwareScanVerdict scanWithFiniteRetry(SealedUpload upload) {
        MalwareScanVerdict last = MalwareScanVerdict.UNAVAILABLE;
        for (int attempt = 0; attempt < maxScanAttempts; attempt++) {
            try {
                last = scanner.scan(upload.quarantinedPath());
            } catch (RuntimeException exception) {
                last = MalwareScanVerdict.UNAVAILABLE;
            }
            if (last == MalwareScanVerdict.CLEAN || last == MalwareScanVerdict.THREAT_DETECTED) {
                return last;
            }
        }
        return last;
    }

    private static AttachmentProcessingOutcome.Rejected rejected(
            AttachmentRejectedCode code,
            boolean quarantinedRetained,
            boolean orphanRetained
    ) {
        return new AttachmentProcessingOutcome.Rejected(code, quarantinedRetained, orphanRetained);
    }
}
