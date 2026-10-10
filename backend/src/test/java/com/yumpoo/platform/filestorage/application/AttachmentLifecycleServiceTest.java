package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.application.AttachmentData.ScanClaim;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanOutcome;
import com.yumpoo.platform.filestorage.domain.AttachmentFileType;
import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.filestorage.domain.AttachmentState;
import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;

class AttachmentLifecycleServiceTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void failedDetectionDoesNotPersistTheTypeCheckpoint() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        QuarantineStorage storage = mock(QuarantineStorage.class);
        AttachmentContentDetector detector = mock(AttachmentContentDetector.class);
        AttachmentLifecycleService service = service(repository, storage, detector);
        ScanClaim claim = claim(null, null);
        SealedUpload sealed = sealed(claim);
        when(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256())).thenReturn(sealed);
        when(detector.detect(sealed.quarantinedPath(), AttachmentFileNamePolicy.normalize("evidence.txt")))
                .thenThrow(new java.io.IOException("controlled detector failure"));

        assertThat(service.scan(claim)).isEqualTo(new ScanOutcome.Rejected(
                com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode.INTEGRITY_CHECK_FAILED));
        verify(repository, never()).recordDetected(eq(claim), eq("text/plain"), any());
        verify(storage, never()).publish(eq(sealed), any());
    }

    @Test
    void persistedTypeCheckpointRecoversAnIdempotentPublish() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        QuarantineStorage storage = mock(QuarantineStorage.class);
        AttachmentContentDetector detector = mock(AttachmentContentDetector.class);
        AttachmentLifecycleService service = service(repository, storage, detector);
        ScanClaim claim = claim("text/plain", null);
        SealedUpload sealed = sealed(claim);
        PublishedBlob published = new PublishedBlob("sha256/aa/aa/" + "a".repeat(64), 8, claim.sha256());
        when(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256())).thenReturn(sealed);
        when(storage.publish(eq(sealed), any())).thenReturn(published);
        when(repository.claimPublish(eq(claim),anyString(),anyString(),any(),any(),any()))
                .thenReturn(true);

        assertThat(service.scan(claim)).isEqualTo(new ScanOutcome.Clean("text/plain", published.storageKey()));
        verify(detector, never()).detect(any(), any());
        verify(repository).recordPublished(eq(claim), eq(published.storageKey()), any());
    }

    @Test
    void publishLeaseContentionRetriesWithoutARejectionThreshold() {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        AttachmentLifecycleService service = service(repository, mock(QuarantineStorage.class),
                mock(AttachmentContentDetector.class));
        Instant now = Instant.parse("2026-08-25T03:00:00Z");
        ScanClaim first = withAttempt(claim(null, null), 1);
        ScanClaim second = withAttempt(claim(null, null), 10);

        service.retryProcessing(first, now);
        service.retryProcessing(second, now);

        verify(repository).retry(first, now.plusSeconds(1), now);
        verify(repository).retry(second, now.plusSeconds(1), now);
        verify(repository, never()).completeRejected(any(), any(), any(), any());
    }

    @Test
    void matchingTypePublishesAndPersistsMimeWithoutAnExternalScanner() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        QuarantineStorage storage = mock(QuarantineStorage.class);
        AttachmentContentDetector detector = mock(AttachmentContentDetector.class);
        AttachmentLifecycleService service = service(repository, storage, detector);
        ScanClaim claim = claim(null, null);
        SealedUpload sealed = sealed(claim);
        PublishedBlob blob = new PublishedBlob("sha256/aa/aa/" + claim.sha256(), 8, claim.sha256());
        when(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256())).thenReturn(sealed);
        when(detector.detect(any(), any())).thenReturn(new DetectedAttachmentContent(AttachmentFileType.TXT, "text/plain"));
        when(repository.claimPublish(eq(claim), anyString(), anyString(), any(), any(), any())).thenReturn(true);
        when(storage.publish(eq(sealed), any())).thenReturn(blob);

        assertThat(service.scan(claim)).isEqualTo(new ScanOutcome.Clean("text/plain", blob.storageKey()));
        verify(repository).recordDetected(eq(claim), eq("text/plain"), any());
        verify(repository).recordPublished(eq(claim), eq(blob.storageKey()), any());
    }

    @Test
    void recoveredPublishedBlobMustPassIntegrityVerification() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        QuarantineStorage storage = mock(QuarantineStorage.class);
        AttachmentContentDetector detector = mock(AttachmentContentDetector.class);
        AttachmentLifecycleService service = service(repository, storage, detector);
        String key = "sha256/aa/aa/" + "a".repeat(64);
        ScanClaim claim = claim("text/plain", key);
        when(storage.resume(claim.attachmentId(), claim.sizeBytes(), claim.sha256())).thenReturn(sealed(claim));
        PublishedBlob blob = new PublishedBlob(key, claim.sizeBytes(), claim.sha256());
        when(storage.verify(blob)).thenReturn(false);
        assertThat(service.scan(claim)).isEqualTo(new ScanOutcome.Rejected(
                com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode.INTEGRITY_CHECK_FAILED));

        when(storage.verify(blob)).thenReturn(true);
        assertThat(service.scan(claim)).isEqualTo(new ScanOutcome.Clean("text/plain", key));
        verify(detector, never()).detect(any(), any());
        verify(storage, never()).publish(any());
    }

    @Test
    void aReceiveFinishingAfterProjectPurgeDiscardsItsLateSealedFile() throws Exception {
        AttachmentRepository repository = mock(AttachmentRepository.class);
        var storage = new LocalFileQuarantineStorage(tempDirectory.resolve("quarantine"), tempDirectory.resolve("blobs"));
        UUID company = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var metadata = new AttachmentRecord(id, company, project, AttachmentOwnerType.WORK_ITEM, UUID.randomUUID(),
                "evidence.txt", "txt", "text/plain", null, null, null, null, AttachmentState.UPLOADING,
                "RECEIVING", null, 8, UUID.randomUUID(), now.plusSeconds(60), null, null,
                UUID.randomUUID(), now.plusSeconds(300), null, null, null, 0, 1, now);
        when(repository.beginUpload(eq(company), eq(id), any(), any(), any())).thenReturn(Optional.of(metadata));
        when(repository.seal(eq(company), eq(id), any(), anyLong(), anyString(), any()))
                .thenThrow(ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_PURGING"));
        when(repository.isProjectPurging(company, project)).thenReturn(true);
        doAnswer(call -> { call.getArgument(4, QuarantineStorage.Mutation.class).run(); return null; })
                .when(repository).mutateUpload(eq(company), eq(id), any(), any(), any());
        var service = service(repository, storage, mock(AttachmentContentDetector.class));

        assertThatThrownBy(() -> service.upload(new AttachmentData.UploadContent(company, id,
                new ByteArrayInputStream("evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)), OptionalLong.of(8), now)))
                .isInstanceOf(ApplicationException.class);
        try (var files = Files.list(tempDirectory.resolve("quarantine"))) { assertThat(files).isEmpty(); }
    }

    private static AttachmentLifecycleService service(AttachmentRepository repository,
            QuarantineStorage storage, AttachmentContentDetector detector) {
        return new AttachmentLifecycleService(repository, storage, detector,
                new AttachmentRuntimeSettings(100L << 30, 10L << 30,
                        Duration.ofMinutes(5), Duration.ofMinutes(5)));
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
