package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.application.AttachmentData.ScanClaim;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanOutcome;
import com.yumpoo.platform.filestorage.domain.AttachmentFileType;
import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;

class AttachmentLifecycleServiceTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void unavailableScanDoesNotPersistTheCleanCheckpoint() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        QuarantineStorage storage = mock(QuarantineStorage.class);
        AttachmentContentDetector detector = mock(AttachmentContentDetector.class);
        MalwareScanner scanner = mock(MalwareScanner.class);
        AttachmentLifecycleService service = service(repository, storage, detector, scanner);
        ScanClaim claim = claim(null, null);
        SealedUpload sealed = sealed(claim);
        when(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256())).thenReturn(sealed);
        when(detector.detect(sealed.quarantinedPath(), AttachmentFileNamePolicy.normalize("evidence.txt")))
                .thenReturn(new DetectedAttachmentContent(AttachmentFileType.TXT, "text/plain"));
        when(scanner.scan(sealed.quarantinedPath())).thenReturn(MalwareScanVerdict.UNAVAILABLE);

        assertThat(service.scan(claim)).isInstanceOf(ScanOutcome.Unavailable.class);
        verify(repository, never()).recordDetected(eq(claim), eq("text/plain"), any());
        verify(storage, never()).publish(sealed);
    }

    @Test
    void persistedCleanCheckpointRecoversAnIdempotentPublishWithoutRescanning() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        QuarantineStorage storage = mock(QuarantineStorage.class);
        AttachmentContentDetector detector = mock(AttachmentContentDetector.class);
        MalwareScanner scanner = mock(MalwareScanner.class);
        AttachmentLifecycleService service = service(repository, storage, detector, scanner);
        ScanClaim claim = claim("text/plain", null);
        SealedUpload sealed = sealed(claim);
        PublishedBlob published = new PublishedBlob("sha256/aa/aa/" + "a".repeat(64), 8, claim.sha256());
        when(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256())).thenReturn(sealed);
        when(storage.publish(sealed)).thenReturn(published);
        when(repository.claimPublish(eq(claim),anyString(),anyString(),any(),any(),any()))
                .thenReturn(true);

        assertThat(service.scan(claim)).isEqualTo(new ScanOutcome.Clean("text/plain", published.storageKey()));
        verify(detector, never()).detect(any(), any());
        verify(scanner, never()).scan(any());
        verify(repository).recordPublished(eq(claim), eq(published.storageKey()), any());
    }

    @Test
    void unavailableAttemptsUseTheConfiguredFiveAndThirtySecondBackoff() {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        AttachmentLifecycleService service = service(repository, mock(QuarantineStorage.class),
                mock(AttachmentContentDetector.class), mock(MalwareScanner.class));
        Instant now = Instant.parse("2026-08-25T03:00:00Z");
        ScanClaim first = withAttempt(claim(null, null), 1);
        ScanClaim second = withAttempt(claim(null, null), 2);

        service.retryOrExhaust(first, now);
        service.retryOrExhaust(second, now);

        verify(repository).retry(first, now.plusSeconds(5), now);
        verify(repository).retry(second, now.plusSeconds(30), now);
    }

    private static AttachmentLifecycleService service(AttachmentRepository repository,
            QuarantineStorage storage, AttachmentContentDetector detector, MalwareScanner scanner) {
        return new AttachmentLifecycleService(repository, storage, detector, scanner,
                new AttachmentRuntimeSettings(100L << 30, 10L << 30,
                        Duration.ofMinutes(5), Duration.ofMinutes(5),
                        Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    private ScanClaim claim(String detectedMime, String storageKey) {
        return new ScanClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AttachmentOwnerType.WORK_ITEM, UUID.randomUUID(), UUID.randomUUID(),
                "evidence.txt", "text/plain", 8, "a".repeat(64), detectedMime, storageKey, 1, 1);
    }

    private SealedUpload sealed(ScanClaim claim) {
        return new SealedUpload(claim.attachmentId(), tempDirectory.resolve("content.sealed"),
                claim.sizeBytes(), claim.sha256());
    }

    private static ScanClaim withAttempt(ScanClaim claim, int attempt) {
        return new ScanClaim(claim.taskId(), claim.leaseToken(), claim.attachmentId(), claim.companyId(),
                claim.projectId(), claim.ownerType(), claim.ownerId(), claim.uploadedByUserId(),
                claim.originalFileName(), claim.declaredMime(), claim.sizeBytes(), claim.sha256(),
                claim.detectedMime(), claim.storageKey(), claim.generation(), attempt);
    }
}
