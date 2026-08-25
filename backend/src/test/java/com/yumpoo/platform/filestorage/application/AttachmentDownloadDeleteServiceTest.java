package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentState;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AttachmentDownloadDeleteServiceTest {
    private final AttachmentRepository repository=mock(AttachmentRepository.class);
    private final QuarantineStorage storage=mock(QuarantineStorage.class);
    private final AttachmentLifecycleService service=new AttachmentLifecycleService(repository,storage,
            mock(AttachmentContentDetector.class),mock(MalwareScanner.class),new AttachmentRuntimeSettings(
            100L<<30,10L<<30,Duration.ofMinutes(5),Duration.ofMinutes(15),
            Duration.ofSeconds(5),Duration.ofSeconds(30)));

    @Test
    void verifiesTheBlobBeforeOpeningTheDownloadStream() throws Exception {
        AttachmentRecord row=mock(AttachmentRecord.class);
        UUID companyId=UUID.randomUUID(),id=UUID.randomUUID();
        when(row.id()).thenReturn(id); when(row.companyId()).thenReturn(companyId);
        when(row.status()).thenReturn(AttachmentState.AVAILABLE); when(row.storageKey()).thenReturn("sha256/aa/aa/"+"a".repeat(64));
        when(row.sha256()).thenReturn("a".repeat(64)); when(row.sizeBytes()).thenReturn(4L);
        when(row.originalFileName()).thenReturn("验收.txt"); when(row.detectedMime()).thenReturn("text/plain");
        when(repository.find(companyId,id)).thenReturn(Optional.of(row));
        PublishedBlob blob=new PublishedBlob(row.storageKey(),4,row.sha256());
        when(storage.inspect(blob)).thenReturn(BlobVerification.VERIFIED);
        when(storage.open(blob)).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3,4}));

        AttachmentBoundaryData.Download download=service.downloadBoundary(companyId,id,Instant.now());

        assertThat(download.inputStream().readAllBytes()).hasSize(4);
        var order=inOrder(storage);
        order.verify(storage).inspect(blob); order.verify(storage).open(blob);
        verify(repository).resolveReconciliationIssues(eq("ATTACHMENT"),eq(id.toString()),any());
    }

    @Test
    void corruptedBlobReturnsOnlyDependencyUnavailableAndRecordsAnIssue() throws Exception {
        AttachmentRecord row=mock(AttachmentRecord.class);
        UUID companyId=UUID.randomUUID(),id=UUID.randomUUID();
        when(row.id()).thenReturn(id); when(row.companyId()).thenReturn(companyId);
        when(row.status()).thenReturn(AttachmentState.AVAILABLE); when(row.storageKey()).thenReturn("sha256/aa/aa/"+"a".repeat(64));
        when(row.sha256()).thenReturn("a".repeat(64)); when(row.sizeBytes()).thenReturn(4L);
        when(repository.find(companyId,id)).thenReturn(Optional.of(row));
        when(storage.inspect(any())).thenReturn(BlobVerification.HASH_MISMATCH);

        assertThatThrownBy(()->service.downloadBoundary(companyId,id,Instant.now()))
                .isInstanceOfSatisfying(ApplicationException.class,error->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(repository).recordReconciliationIssue(eq("HASH_MISMATCH"),eq("ATTACHMENT"),
                eq(id.toString()),eq(id),eq(companyId),any());
        verify(storage,never()).open(any());
    }

    @Test
    void deleteNormalizesReasonAndReturnsATombstone() {
        UUID companyId=UUID.randomUUID(),id=UUID.randomUUID(),actor=UUID.randomUUID();
        AttachmentRecord before=mock(AttachmentRecord.class),after=mock(AttachmentRecord.class);
        when(before.rowVersion()).thenReturn(7L); when(after.id()).thenReturn(id);
        when(after.status()).thenReturn(AttachmentState.DELETED); when(after.deletedByUserId()).thenReturn(actor);
        when(after.deletedAt()).thenReturn(Instant.parse("2026-08-25T08:00:00Z"));
        when(after.deleteReason()).thenReturn("重复附件"); when(after.rowVersion()).thenReturn(8L);
        when(repository.find(companyId,id)).thenReturn(Optional.of(before));
        when(repository.delete(eq(companyId),eq(id),eq(actor),eq("重复附件"),eq(7L),any())).thenReturn(after);

        AttachmentBoundaryData.Deleted deleted=service.deleteBoundary(new AttachmentBoundaryData.Delete(
                companyId,id,actor,"  重复附件  ",7,Instant.now()));

        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(deleted.previousRowVersion()).isEqualTo(7);
        assertThat(deleted.etag()).isEqualTo("\"8\"");
    }
}
